export {};
type SupportedStock = {
  symbol: string;
  name: string;
  sector: string;
  category: string;
  popular: boolean;
};
type MarketInstrument = {
  symbol: string;
  name: string;
  exchange: string | null;
  curated: boolean;
};
type InstrumentSearchResponse = {
  resolution: 'resolved' | 'not_found';
  results: MarketInstrument[];
};
type ResolutionError = {
  resolution?: 'not_found' | 'rate_limited';
  retryAfter?: string;
};


const app = document.querySelector<HTMLElement>('[data-finance-landing]');

if (app) {
  const copy = JSON.parse(app.dataset.copy || '{}') as { prompts?: string[]; discoveredCompany?: string };
  const researchPrompts = copy.prompts || [];
  const randomHeading = app.querySelector<HTMLElement>('[data-random-heading]');
  if (randomHeading && researchPrompts.length) randomHeading.textContent = researchPrompts[Math.floor(Math.random() * researchPrompts.length)];
  const stocks = JSON.parse(app.dataset.stocks || '[]') as SupportedStock[];
  const configuredBase = app.dataset.apiBase?.replace(/\/$/, '') || 'https://server.batyrbek.com';
  const apiBase = window.location.hostname === 'server.batyrbek.com' ? window.location.origin : configuredBase;
  const input = app.querySelector<HTMLInputElement>('[data-catalog-input]')!;
  const form = app.querySelector<HTMLFormElement>('[data-catalog-form]')!;
  const results = app.querySelector<HTMLElement>('[data-catalog-results]')!;
  const status = app.querySelector<HTMLElement>('[data-catalog-status]')!;
  let visible: MarketInstrument[] = [];
  let searchTimer = 0;
  let searchSequence = 0;
  let retryCountdown = 0;
  let retryTimer = 0;

  const localMatches = (query: string) => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return [];
    return stocks.filter(stock => stock.symbol.toLowerCase().startsWith(normalized)
      || stock.name.toLowerCase().includes(normalized)).slice(0, 8)
      .map(stock => ({ symbol: stock.symbol, name: stock.name, exchange: stock.sector, curated: true }));
  };
  const researchUrl = (instrument: MarketInstrument) => instrument.curated
    ? `/finance/${instrument.symbol}`
    : `/finance/ticker?symbol=${encodeURIComponent(instrument.symbol)}`;

  const renderResults = (matches: MarketInstrument[]) => {
    visible = matches;
    results.replaceChildren(...matches.map(instrument => {
      const link = document.createElement('a');
      link.className = 'catalog-result'; link.href = researchUrl(instrument); link.setAttribute('role', 'option');
      const symbol = document.createElement('b'); symbol.textContent = instrument.symbol;
      const details = document.createElement('span');
      const name = document.createElement('strong'); name.textContent = instrument.name;
      const source = document.createElement('small'); source.textContent = instrument.curated
        ? instrument.exchange || 'Curated company' : [instrument.exchange, 'Broader catalog'].filter(Boolean).join(' · ');
      details.append(name, source);
      const cue = document.createElement('i'); cue.className = 'arrow-icon'; cue.setAttribute('aria-hidden', 'true');
      link.append(symbol, details, cue); return link;
    }));
    results.hidden = matches.length === 0;
  };

  const clearRetryTimers = () => {
    window.clearInterval(retryCountdown);
    window.clearTimeout(retryTimer);
    retryCountdown = 0;
    retryTimer = 0;
  };

  const showRateLimit = (retryAfter: string | null, query: string, sequence: number, autoRetry: boolean) => {
    clearRetryTimers();
    const parsed = retryAfter ? Date.parse(retryAfter) : Number.NaN;
    const deadline = Number.isFinite(parsed) ? parsed : Date.now() + 60_000;
    const update = () => {
      if (sequence !== searchSequence) {
        clearRetryTimers();
        return;
      }
      const seconds = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      if (seconds > 0) {
        status.textContent = `Broader search available in approximately ${seconds} second${seconds === 1 ? '' : 's'}.`;
        status.dataset.kind = 'neutral';
        return;
      }
      window.clearInterval(retryCountdown);
      retryCountdown = 0;
      if (autoRetry) {
        status.textContent = 'Checking the broader company catalog…';
        retryTimer = window.setTimeout(() => { void search(query, false); }, 0);
      } else {
        status.textContent = 'A provider slot should now be available. Search again to retry.';
      }
    };
    update();
    if (deadline > Date.now()) retryCountdown = window.setInterval(update, 1000);
  };

  const search = async (query: string, allowAutoRetry = true) => {
    clearRetryTimers();
    const normalized = query.trim();
    if (!normalized) {
      renderResults([]); status.textContent = ''; status.dataset.kind = 'neutral'; return;
    }
    const sequence = ++searchSequence;
    const local = localMatches(normalized); renderResults(local);
    status.textContent = local.length ? `${local.length} local match${local.length === 1 ? '' : 'es'} found.` : 'Searching the company catalog…';
    status.dataset.kind = 'neutral';
    try {
      const response = await fetch(`${apiBase}/api/stocks/search?q=${encodeURIComponent(normalized)}`, { headers: { Accept: 'application/json' } });
      const payload = await response.json() as InstrumentSearchResponse | ResolutionError;
      if (!response.ok) {
        if (response.status === 429 && payload.resolution === 'rate_limited') {
          if (sequence === searchSequence) showRateLimit(payload.retryAfter || null, normalized, sequence, allowAutoRetry);
          return;
        }
        throw new Error('search unavailable');
      }
      const remote = (payload as InstrumentSearchResponse).results;
      if (sequence !== searchSequence) return;
      renderResults(remote);
      status.textContent = payload.resolution === 'not_found' ? 'No active supported company matches that search.'
        : `${remote.length} match${remote.length === 1 ? '' : 'es'} found.`;
      status.dataset.kind = payload.resolution === 'not_found' ? 'error' : 'neutral';
    } catch {
      if (sequence !== searchSequence || local.length) return;
      status.textContent = 'Broader search is temporarily unavailable. Try again shortly.'; status.dataset.kind = 'error';
    }
  };

  input.addEventListener('input', () => {
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => { void search(input.value); }, 250);
  });
  input.addEventListener('focus', () => { if (input.value.trim()) void search(input.value); });
  input.addEventListener('keydown', event => {
    if (event.key !== 'ArrowDown' || results.hidden) return;
    event.preventDefault(); results.querySelector<HTMLAnchorElement>('a')?.focus();
  });
  document.addEventListener('click', event => { if (!form.contains(event.target as Node)) results.hidden = true; });
  form.addEventListener('submit', event => {
    event.preventDefault();
    const query = input.value.trim();
    const exact = visible.find(item => item.symbol.toLowerCase() === query.toLowerCase()
      || item.name.toLowerCase() === query.toLowerCase());
    if (exact) window.location.href = researchUrl(exact);
    else void search(query);
  });

  const sectorResults = app.querySelector<HTMLElement>('[data-sector-results]')!;
  app.querySelectorAll<HTMLButtonElement>('[data-sector]').forEach(button => {
    button.addEventListener('click', () => {
      const sector = button.dataset.sector || '';
      app.querySelectorAll<HTMLButtonElement>('[data-sector]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
      const sectorStocks = stocks.filter(stock => stock.sector === sector);
      sectorResults.replaceChildren(...sectorStocks.map(stock => {
        const link = document.createElement('a'); link.className = 'sector-card'; link.href = `/finance/${stock.symbol}`;
        const symbol = document.createElement('b'); symbol.textContent = stock.symbol;
        const name = document.createElement('strong'); name.textContent = stock.name;
        const category = document.createElement('small'); category.textContent = stock.category;
        const cue = document.createElement('i'); cue.className = 'arrow-icon'; cue.setAttribute('aria-hidden', 'true');
        link.append(symbol, name, category, cue); return link;
      }));
      sectorResults.hidden = false; sectorResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  });

  const recentSymbols = JSON.parse(localStorage.getItem('finance-recent') || '[]') as string[];
  if (recentSymbols.length) {
    const recentSection = app.querySelector<HTMLElement>('[data-recent-section]')!;
    const recentList = app.querySelector<HTMLElement>('[data-recent-list]')!;
    recentList.replaceChildren(...recentSymbols.slice(0, 6).map(symbolValue => {
      const stock = stocks.find(item => item.symbol === symbolValue);
      const link = document.createElement('a'); link.className = 'recent-card';
      link.href = stock ? `/finance/${symbolValue}` : `/finance/ticker?symbol=${encodeURIComponent(symbolValue)}`;
      const symbol = document.createElement('b'); symbol.textContent = symbolValue;
      const name = document.createElement('span'); name.textContent = stock?.name || copy.discoveredCompany || '';
      const cue = document.createElement('i'); cue.className = 'arrow-icon'; cue.setAttribute('aria-hidden', 'true');
      link.append(symbol, name, cue); return link;
    }));
    recentSection.hidden = false;
  }
}
