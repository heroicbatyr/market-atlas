export {};
type StockOverview = {
  ticker: string; companyName: string | null; currency: string | null; price: number | null;
  change: number | null; changePercent: number | null; marketCap: number | null;
  peRatio: number | null; eps: number | null; volume: number | null;
  week52High: number | null; week52Low: number | null; exchange: string | null;
  sector: string | null; industry: string | null; description: string | null;
  ceo: string | null; employees: number | null; headquarters: string | null;
  website: string | null; revenueGrowth: number | null; netMargin: number | null;
  freeCashFlow: number | null; source: string | null; updatedAt: string;
  priceDate: string | null; nextCheckAt: string | null; stale: boolean;
};
type PricePoint = { date: string; close: number };
type StockHistory = {
  ticker: string; currency: string | null; range: string; resolution: string;
  points: PricePoint[]; updatedAt: string; dataAsOf: string | null; nextCheckAt: string | null; stale: boolean;
};
type AnnualFinancial = {
  date: string; fiscalYear: string | null; revenue: number | null; operatingIncome: number | null;
  netIncome: number | null; eps: number | null; freeCashFlow: number | null;
  revenueGrowth: number | null; netMargin: number | null;
  filingUrl?: string | null; filingForm?: string | null; filedAt?: string | null;
};
type CompanyFinancials = {
  symbol: string; currency: string | null; annual: AnnualFinancial[];
  cashAndEquivalents: number | null; totalDebt: number | null; debtToEquity: number | null;
  source: string; fetchedAt: string; dataAsOf: string; stale: boolean;
};
type QuarterlyEarnings = { periodEnd: string; fiscalPeriod: string; reportedEps: number | null;
  revenue: number | null; netIncome: number | null; filingUrl: string | null; filedAt: string | null };
type CompanyEarnings = { symbol: string; currency: string | null; reported: QuarterlyEarnings[];
  expectedDate: string | null; source: string; fetchedAt: string; stale: boolean };
type SupportedStock = { symbol: string; name: string };
type ApiError = { code?: string; message?: string };
type ChartRange = '1w' | '1m' | '6m' | 'ytd' | '1y' | '2y' | '5y';
type CompanyTab = 'overview' | 'financials' | 'earnings';

class ApiRequestError extends Error {
  constructor(public readonly code: string, message: string) { super(message); }
}

const rangeLabels: Record<ChartRange, string> = {
  '1w': 'One week.', '1m': 'One month.', '6m': 'Six months.',
  ytd: 'Year to date.', '1y': 'One year.', '2y': 'Two years.', '5y': 'Five years.'
};

const app = document.querySelector<HTMLElement>('[data-finance-app]');
if (app) {
  const requestedTicker = new URLSearchParams(window.location.search).get('symbol')?.trim().toUpperCase();
  const ticker = app.dataset.genericTicker === 'true' && requestedTicker && /^[A-Z0-9.-]{1,10}$/.test(requestedTicker)
    ? requestedTicker
    : app.dataset.initialTicker || 'NVDA';
  const isGenericTicker = app.dataset.genericTicker === 'true';
  const configuredBase = app.dataset.apiBase?.replace(/\/$/, '') || 'https://server.batyrbek.com';
  const apiBase = window.location.hostname === 'server.batyrbek.com' ? window.location.origin : configuredBase;
  const snapshotBase = ['batyrbek.com', 'www.batyrbek.com'].includes(window.location.hostname) ? window.location.origin : null;
  const catalog = JSON.parse(app.dataset.stocks || '[]') as SupportedStock[];
  const status = app.querySelector<HTMLElement>('[data-status]')!;
  const results = app.querySelector<HTMLElement>('[data-results]')!;
  const emptyState = app.querySelector<HTMLElement>('[data-empty-state]')!;
  const chartWrap = app.querySelector<HTMLElement>('[data-chart-wrap]')!;
  app.querySelector<HTMLButtonElement>('[data-range="2y"]')!.hidden = !isGenericTicker;
  app.querySelector<HTMLButtonElement>('[data-range="5y"]')!.hidden = isGenericTicker;

  let currentOverview: StockOverview | null = null;
  let currentHistory: StockHistory | null = null;
  let currentFinancials: CompanyFinancials | null = null;
  let currentEarnings: CompanyEarnings | null = null;
  let activeRange: ChartRange = '1y';
  let financialsPromise: Promise<void> | null = null;
  let earningsPromise: Promise<void> | null = null;
  let historyPromise: Promise<void> | null = null;

  const text = (selector: string, value: string) => {
    const element = app.querySelector<HTMLElement>(selector);
    if (element) element.textContent = value;
  };
  const number = (value: number | null, digits = 2) => value == null ? '-'
    : new Intl.NumberFormat('en-US', { maximumFractionDigits: digits }).format(value);
  const money = (value: number | null, currency: string | null, compact = false) => {
    if (value == null) return '-';
    try {
      return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency || 'USD',
        notation: compact ? 'compact' : 'standard', maximumFractionDigits: 2 }).format(value);
    } catch { return `${number(value)} ${currency || ''}`.trim(); }
  };
  const compactNumber = (value: number | null) => value == null ? '-'
    : new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }).format(value);
  const percent = (value: number | null) => value == null ? '-'
    : `${value > 0 ? '+' : ''}${number(value * 100, 1)}%`;
  const fetchJson = async <T>(url: string): Promise<T> => {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    const body = await response.json().catch(() => ({})) as T & ApiError;
    if (!response.ok) throw new ApiRequestError(body.code || 'PROVIDER_UNAVAILABLE', body.message || 'Market data is temporarily unavailable.');
    return body;
  };
  const fetchMarketData = async <T>(url: string, kind: 'overview' | 'history'): Promise<T> => {
    try { return await fetchJson<T>(url); }
    catch (primaryError) {
      const canUseSnapshot = !(primaryError instanceof ApiRequestError)
        || ['PROVIDER_RATE_LIMITED', 'PROVIDER_UNAVAILABLE'].includes(primaryError.code);
      if (!snapshotBase || !canUseSnapshot) throw primaryError;
      try {
        return await fetchJson<T>(`${snapshotBase}/api/finance-snapshot?ticker=${encodeURIComponent(ticker)}&kind=${kind}`);
      } catch { throw primaryError; }
    }
  };
  const friendlyError = (error: unknown) => {
    if (!(error instanceof ApiRequestError)) return 'Market data is temporarily unavailable. Please try again.';
    if (error.code === 'TICKER_NOT_FOUND') return 'No public company was found for that ticker.';
    if (error.code === 'UNSUPPORTED_TICKER') return 'That company is not in the currently supported research universe.';
    if (error.code === 'UNSUPPORTED_INSTRUMENT') return 'Market Atlas currently supports active U.S. common stocks, not funds, OTC listings, or inactive securities.';
    if (error.code === 'PROVIDER_RATE_LIMITED') return 'The market-data provider is rate limited. Cached data will remain available.';
    if (error.code === 'INVALID_TICKER') return 'Enter a valid ticker using up to 10 letters, numbers, periods, or hyphens.';
    return 'Market data is temporarily unavailable. Please try again.';
  };
  const formatUpdatedTime = (value: string) => new Intl.DateTimeFormat('en', { hour: 'numeric', minute: '2-digit' }).format(new Date(value));
  const staleMessage = (updatedAt: string) => `Live provider temporarily unavailable - showing cached data from ${formatUpdatedTime(updatedAt)}.`;
  const setMetric = (name: keyof StockOverview, selector: string, formatted: string, stock: StockOverview) => {
    const card = app.querySelector<HTMLElement>(`[data-metric="${name}"]`);
    if (card) card.hidden = stock[name] == null;
    text(selector, formatted);
  };
  const setProfile = (name: keyof StockOverview, selector: string, formatted: string, stock: StockOverview) => {
    const card = app.querySelector<HTMLElement>(`[data-profile="${name}"]`);
    if (card) card.hidden = stock[name] == null;
    text(selector, formatted);
  };

  const renderOverview = (stock: StockOverview) => {
    const change = stock.changePercent;
    const direction = change == null || change === 0 ? 'neutral' : change > 0 ? 'positive' : 'negative';
    text('[data-company-name]', stock.companyName || stock.ticker);
    if (isGenericTicker) document.title = `${stock.companyName || stock.ticker} (${stock.ticker}) - Market Atlas`;
    text('[data-company-ticker]', stock.ticker);
    text('[data-company-classification]', [stock.exchange, stock.sector, stock.industry].filter(Boolean).join(' · ') || 'Public company');
    text('[data-price]', money(stock.price, stock.currency));
    text('[data-change]', change == null ? '-' : `${change > 0 ? '+' : ''}${number(change)}% today`);
    const changeElement = app.querySelector<HTMLElement>('[data-change]');
    if (changeElement) changeElement.dataset.direction = direction;
    setMetric('marketCap', '[data-market-cap]', money(stock.marketCap, stock.currency, true), stock);
    setMetric('peRatio', '[data-pe]', number(stock.peRatio), stock);
    setMetric('eps', '[data-eps]', money(stock.eps, stock.currency), stock);
    setMetric('volume', '[data-volume]', compactNumber(stock.volume), stock);
    setMetric('revenueGrowth', '[data-revenue-growth]', percent(stock.revenueGrowth), stock);
    setMetric('netMargin', '[data-net-margin]', percent(stock.netMargin), stock);
    setMetric('freeCashFlow', '[data-free-cash-flow]', money(stock.freeCashFlow, stock.currency, true), stock);
    setMetric('week52Low', '[data-week-low]', money(stock.week52Low, stock.currency), stock);
    setMetric('week52High', '[data-week-high]', money(stock.week52High, stock.currency), stock);
    text('[data-description]', stock.description || 'A detailed company description is not available from the current provider.');
    setProfile('ceo', '[data-ceo]', stock.ceo || '-', stock);
    setProfile('employees', '[data-employees]', stock.employees == null ? '-' : compactNumber(stock.employees), stock);
    setProfile('headquarters', '[data-headquarters]', stock.headquarters || '-', stock);
    const websiteCard = app.querySelector<HTMLElement>('[data-profile="website"]');
    const website = app.querySelector<HTMLAnchorElement>('[data-website]');
    if (websiteCard) websiteCard.hidden = !stock.website;
    if (website && stock.website) { website.href = stock.website; website.textContent = stock.website.replace(/^https?:\/\//, '').replace(/\/$/, ''); }
    text('[data-source]', stock.source || 'FMP');
    const priceTimestamp = stock.priceDate ? `${stock.priceDate}T00:00:00Z` : stock.updatedAt;
    text('[data-updated]', new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(priceTimestamp)));
  };

  const svgElement = <K extends keyof SVGElementTagNameMap>(name: K) => document.createElementNS('http://www.w3.org/2000/svg', name);
  const pointDate = (point: PricePoint) => new Date(`${point.date}T00:00:00Z`);
  const rangeStart = (range: ChartRange, end: Date) => {
    const start = new Date(end);
    if (range === '1w') start.setUTCDate(start.getUTCDate() - 7);
    if (range === '1m') start.setUTCMonth(start.getUTCMonth() - 1);
    if (range === '6m') start.setUTCMonth(start.getUTCMonth() - 6);
    if (range === 'ytd') start.setUTCMonth(0, 1);
    if (range === '1y') start.setUTCFullYear(start.getUTCFullYear() - 1);
    if (range === '2y') start.setUTCFullYear(start.getUTCFullYear() - 2);
    if (range === '5y') start.setUTCFullYear(start.getUTCFullYear() - 5);
    return start;
  };
  const pointsForRange = (history: StockHistory, range: ChartRange) => {
    const all = history.points.filter(point => Number.isFinite(point.close));
    if (!all.length) return all;
    const start = rangeStart(range, pointDate(all.at(-1)!));
    const visible = all.filter(point => pointDate(point) >= start);
    if (visible.length >= 2) return visible;
    const previous = all.filter(point => pointDate(point) < start).at(-1);
    return previous ? [previous, ...visible] : visible;
  };
  const appendText = (svg: SVGSVGElement, value: string, x: number, y: number, anchor: string) => {
    const label = svgElement('text'); label.textContent = value;
    label.setAttribute('x', String(x)); label.setAttribute('y', String(y));
    label.setAttribute('text-anchor', anchor); label.setAttribute('class', 'chart-axis-label'); svg.append(label);
  };
  const renderChart = (history: StockHistory) => {
    const svg = app.querySelector<SVGSVGElement>('[data-chart]')!;
    const tooltip = app.querySelector<HTMLElement>('[data-chart-tooltip]')!;
    const points = pointsForRange(history, activeRange);
    const currency = history.currency || currentOverview?.currency || 'USD';
    svg.replaceChildren(); tooltip.hidden = true;
    text('[data-chart-heading]', rangeLabels[activeRange]);
    text('[data-resolution]', history.resolution === 'weekly' ? 'Weekly close' : 'Daily close');
    app.querySelectorAll<HTMLButtonElement>('[data-range]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.range === activeRange)));
    if (points.length < 2) { text('[data-chart-status]', 'Not enough historical data is available for this period.'); text('[data-performance]', '-'); return; }
    text('[data-chart-status]', '');
    const performance = ((points.at(-1)!.close / points[0].close) - 1) * 100;
    const performanceElement = app.querySelector<HTMLElement>('[data-performance]')!;
    performanceElement.textContent = `${performance > 0 ? '+' : ''}${number(performance)}% over ${activeRange.toUpperCase()}`;
    performanceElement.dataset.direction = performance === 0 ? 'neutral' : performance > 0 ? 'positive' : 'negative';
    const width = Math.max(chartWrap.clientWidth, 320); const height = Math.max(chartWrap.clientHeight, 240);
    const plot = { left: 8, top: 14, right: width - 72, bottom: height - 34 };
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    const values = points.map(point => point.close); const rawMin = Math.min(...values); const rawMax = Math.max(...values);
    const padding = (rawMax - rawMin || rawMax * .05 || 1) * .08; const min = rawMin - padding; const max = rawMax + padding; const spread = max - min;
    const startTime = pointDate(points[0]).getTime(); const endTime = pointDate(points.at(-1)!).getTime(); const timeSpread = endTime - startTime || 1;
    const coordinates = points.map(point => ({ x: plot.left + ((pointDate(point).getTime() - startTime) / timeSpread) * (plot.right - plot.left), y: plot.top + ((max - point.close) / spread) * (plot.bottom - plot.top) }));
    for (let index = 0; index < 4; index += 1) {
      const y = plot.top + index / 3 * (plot.bottom - plot.top);
      const grid = svgElement('line'); grid.setAttribute('x1', String(plot.left)); grid.setAttribute('x2', String(plot.right)); grid.setAttribute('y1', String(y)); grid.setAttribute('y2', String(y)); grid.setAttribute('class', 'chart-grid-line'); svg.append(grid);
      appendText(svg, money(max - index / 3 * spread, currency), width - 4, y + 3, 'end');
    }
    const dateFormatter = new Intl.DateTimeFormat('en', activeRange === '1w' || activeRange === '1m' ? { month: 'short', day: 'numeric', timeZone: 'UTC' } : { month: 'short', year: '2-digit', timeZone: 'UTC' });
    const ticks = width < 560 ? 3 : 5;
    for (let index = 0; index < ticks; index += 1) { const ratio = index / (ticks - 1); appendText(svg, dateFormatter.format(new Date(startTime + ratio * timeSpread)), plot.left + ratio * (plot.right - plot.left), height - 8, index === 0 ? 'start' : index === ticks - 1 ? 'end' : 'middle'); }
    const path = coordinates.map((point, index) => `${index ? 'L' : 'M'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ');
    const area = svgElement('path'); area.setAttribute('d', `${path} L ${coordinates.at(-1)!.x} ${plot.bottom} L ${coordinates[0].x} ${plot.bottom} Z`); area.setAttribute('class', 'chart-area');
    const line = svgElement('path'); line.setAttribute('d', path); line.setAttribute('class', 'chart-line');
    const crosshair = svgElement('line'); crosshair.setAttribute('y1', String(plot.top)); crosshair.setAttribute('y2', String(plot.bottom)); crosshair.setAttribute('class', 'chart-crosshair'); crosshair.setAttribute('visibility', 'hidden');
    const dot = svgElement('circle'); dot.setAttribute('r', '4'); dot.setAttribute('class', 'chart-hover-dot'); dot.setAttribute('visibility', 'hidden'); svg.append(area, line, crosshair, dot);
    let selectedIndex = points.length - 1;
    const showPoint = (index: number) => {
      selectedIndex = Math.max(0, Math.min(points.length - 1, index)); const coordinate = coordinates[selectedIndex]; const point = points[selectedIndex];
      crosshair.removeAttribute('visibility'); dot.removeAttribute('visibility'); tooltip.hidden = false;
      crosshair.setAttribute('x1', String(coordinate.x)); crosshair.setAttribute('x2', String(coordinate.x)); dot.setAttribute('cx', String(coordinate.x)); dot.setAttribute('cy', String(coordinate.y));
      text('[data-tooltip-date]', new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(pointDate(point)));
      text('[data-tooltip-price]', money(point.close, currency));
      const tooltipWidth = tooltip.offsetWidth || 136; tooltip.style.left = `${Math.max(0, Math.min(width - tooltipWidth, coordinate.x - tooltipWidth / 2))}px`; tooltip.style.top = `${Math.max(4, coordinate.y - 72)}px`;
    };
    const hidePoint = () => { crosshair.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); tooltip.hidden = true; };
    chartWrap.onpointermove = event => { const x = event.clientX - chartWrap.getBoundingClientRect().left; showPoint(coordinates.reduce((best, point, index) => Math.abs(point.x - x) < Math.abs(coordinates[best].x - x) ? index : best, 0)); };
    chartWrap.onpointerleave = hidePoint;
    chartWrap.onkeydown = event => { if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return; event.preventDefault(); showPoint(selectedIndex + (event.key === 'ArrowRight' ? 1 : -1)); };
    svg.setAttribute('aria-label', `${history.ticker} ${activeRange.toUpperCase()} closing price chart, ${number(performance)} percent`);
  };

  const renderMiniChart = (selector: string, annual: AnnualFinancial[], key: 'revenue' | 'netIncome' | 'freeCashFlow') => {
    const svg = app.querySelector<SVGSVGElement>(`[data-financial-chart="${selector}"]`)!;
    const points = annual.map(item => ({ year: item.fiscalYear || item.date.slice(0, 4), value: item[key] })).filter((item): item is { year: string; value: number } => item.value != null);
    svg.replaceChildren(); if (points.length < 2) return;
    const width = 300; const height = 150; const plot = { left: 4, right: 296, top: 10, bottom: 126 };
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    const values = points.map(point => point.value); const min = Math.min(0, ...values); const max = Math.max(0, ...values); const spread = max - min || 1;
    const coords = points.map((point, index) => ({ x: plot.left + index / (points.length - 1) * (plot.right - plot.left), y: plot.top + (max - point.value) / spread * (plot.bottom - plot.top) }));
    const zeroY = plot.top + max / spread * (plot.bottom - plot.top); const grid = svgElement('line'); grid.setAttribute('x1', '4'); grid.setAttribute('x2', '296'); grid.setAttribute('y1', String(zeroY)); grid.setAttribute('y2', String(zeroY)); grid.setAttribute('class', 'financial-grid'); svg.append(grid);
    const path = svgElement('path'); path.setAttribute('d', coords.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ')); path.setAttribute('class', 'financial-line'); svg.append(path);
    points.forEach((point, index) => { const label = svgElement('text'); label.textContent = point.year; label.setAttribute('x', String(coords[index].x)); label.setAttribute('y', '146'); label.setAttribute('text-anchor', index === 0 ? 'start' : index === points.length - 1 ? 'end' : 'middle'); label.setAttribute('class', 'financial-axis'); svg.append(label); });
  };
  const renderFinancials = (financials: CompanyFinancials) => {
    const annual = [...financials.annual].sort((a, b) => a.date.localeCompare(b.date)).slice(-5);
    const latest = annual.at(-1);
    text('[data-latest-revenue]', money(latest?.revenue ?? null, financials.currency, true));
    text('[data-latest-net-income]', money(latest?.netIncome ?? null, financials.currency, true));
    text('[data-latest-fcf]', money(latest?.freeCashFlow ?? null, financials.currency, true));
    renderMiniChart('revenue', annual, 'revenue'); renderMiniChart('netIncome', annual, 'netIncome'); renderMiniChart('freeCashFlow', annual, 'freeCashFlow');
    const tbody = app.querySelector<HTMLTableSectionElement>('[data-financial-rows]')!;
    tbody.replaceChildren(...[...annual].reverse().map(row => {
      const tr = document.createElement('tr');
      [row.fiscalYear || row.date.slice(0, 4), money(row.revenue, financials.currency, true), money(row.operatingIncome, financials.currency, true), money(row.netIncome, financials.currency, true), money(row.eps, financials.currency), money(row.freeCashFlow, financials.currency, true)].forEach(value => { const td = document.createElement('td'); td.textContent = value; tr.append(td); });
      const sourceCell = document.createElement('td');
      if (row.filingUrl?.startsWith('https://www.sec.gov/Archives/edgar/')) {
        const link = document.createElement('a'); link.href = row.filingUrl;
        link.textContent = row.filingForm || 'SEC filing'; link.target = '_blank'; link.rel = 'noopener noreferrer';
        if (row.filedAt) link.title = `Filed ${row.filedAt}`;
        sourceCell.append(link);
      } else sourceCell.textContent = '-';
      tr.append(sourceCell);
      return tr;
    }));
    text('[data-cash]', money(financials.cashAndEquivalents, financials.currency, true));
    text('[data-debt]', money(financials.totalDebt, financials.currency, true));
    text('[data-debt-equity]', number(financials.debtToEquity));
    text('[data-financial-source]', financials.source || 'FMP');
    text('[data-financial-as-of]', financials.dataAsOf ? new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${financials.dataAsOf}T00:00:00Z`)) : '-');
    app.querySelector<HTMLElement>('[data-financials-content]')!.hidden = false;
  };

  const renderEarnings = (earnings: CompanyEarnings) => {
    const date = (value: string) => new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${value}T00:00:00Z`));
    text('[data-expected-earnings]', earnings.expectedDate ? date(earnings.expectedDate) + ' (estimated)' : 'Not available');
    const rows = earnings.reported.slice(0, 8).map(row => {
      const tr = document.createElement('tr');
      [date(row.periodEnd), row.fiscalPeriod, money(row.revenue, earnings.currency, true),
        money(row.netIncome, earnings.currency, true), money(row.reportedEps, earnings.currency)].forEach(value => {
        const td = document.createElement('td'); td.textContent = value; tr.append(td);
      });
      const filing = document.createElement('td');
      if (row.filingUrl?.startsWith('https://www.sec.gov/Archives/edgar/')) {
        const link = document.createElement('a'); link.href = row.filingUrl; link.textContent = '10-Q';
        link.target = '_blank'; link.rel = 'noopener noreferrer';
        if (row.filedAt) link.title = `Filed ${row.filedAt}`;
        filing.append(link);
      } else filing.textContent = '-';
      tr.append(filing); return tr;
    });
    app.querySelector<HTMLTableSectionElement>('[data-earnings-rows]')!.replaceChildren(...rows);
    app.querySelector<HTMLElement>('[data-earnings-content]')!.hidden = false;
  };

  const rememberTicker = () => {
    const recent = JSON.parse(localStorage.getItem('finance-recent') || '[]') as string[];
    localStorage.setItem('finance-recent', JSON.stringify([ticker, ...recent.filter(item => item !== ticker)].slice(0, 6)));
  };
  const loadOverview = async () => {
    status.textContent = `Loading ${ticker} overview…`; status.dataset.kind = 'loading'; app.setAttribute('aria-busy', 'true');
    try {
      currentOverview = await fetchMarketData<StockOverview>(`${apiBase}/api/stocks/${encodeURIComponent(ticker)}`, 'overview');
      renderOverview(currentOverview); rememberTicker(); emptyState.hidden = true; results.hidden = false;
      status.textContent = currentOverview.stale ? staleMessage(currentOverview.updatedAt) : `${ticker} overview loaded.`;
      status.dataset.kind = currentOverview.stale ? 'notice' : 'success';
    } catch (error) { status.textContent = friendlyError(error); status.dataset.kind = 'error'; if (!currentOverview) { results.hidden = true; emptyState.hidden = false; } }
    finally { app.removeAttribute('aria-busy'); }
  };
  const loadHistory = () => {
    if (historyPromise || currentHistory) return historyPromise;
    historyPromise = fetchMarketData<StockHistory>(`${apiBase}/api/stocks/${encodeURIComponent(ticker)}/history?range=5y`, 'history')
      .then(history => { currentHistory = history; renderChart(history); })
      .catch(() => { text('[data-chart-status]', 'Price history is temporarily unavailable. Company metrics remain available.'); })
      .then(() => undefined);
    return historyPromise;
  };
  const loadFinancials = () => {
    if (financialsPromise || currentFinancials) return financialsPromise;
    const financialStatus = app.querySelector<HTMLElement>('[data-financials-status]')!;
    financialStatus.textContent = `Loading ${ticker} annual statements…`; financialStatus.dataset.kind = 'loading';
    financialsPromise = fetchJson<CompanyFinancials>(`${apiBase}/api/stocks/${encodeURIComponent(ticker)}/financials`)
      .then(financials => { currentFinancials = financials; renderFinancials(financials); financialStatus.textContent = financials.stale ? staleMessage(financials.fetchedAt) : `${ticker} annual financials loaded.`; financialStatus.dataset.kind = financials.stale ? 'notice' : 'success'; })
      .catch(error => { financialStatus.textContent = friendlyError(error); financialStatus.dataset.kind = 'error'; financialsPromise = null; })
      .then(() => undefined);
    return financialsPromise;
  };
  const loadEarnings = () => {
    if (earningsPromise || currentEarnings) return earningsPromise;
    const earningsStatus = app.querySelector<HTMLElement>('[data-earnings-status]')!;
    earningsStatus.textContent = `Loading ${ticker} reported quarters…`; earningsStatus.dataset.kind = 'loading';
    earningsPromise = fetchJson<CompanyEarnings>(`${apiBase}/api/stocks/${encodeURIComponent(ticker)}/earnings`)
      .then(earnings => { currentEarnings = earnings; renderEarnings(earnings);
        earningsStatus.textContent = earnings.stale ? staleMessage(earnings.fetchedAt) : `${ticker} reported quarters loaded.`;
        earningsStatus.dataset.kind = earnings.stale ? 'notice' : 'success'; })
      .catch(error => { earningsStatus.textContent = friendlyError(error); earningsStatus.dataset.kind = 'error'; earningsPromise = null; })
      .then(() => undefined);
    return earningsPromise;
  };
  const selectedTab = (): CompanyTab => {
    const tab = new URLSearchParams(window.location.search).get('tab');
    return tab === 'financials' || tab === 'earnings' ? tab : 'overview';
  };
  const showTab = (tab: CompanyTab, updateUrl = false) => {
    app.querySelectorAll<HTMLElement>('[data-tab-panel]').forEach(panel => { panel.hidden = panel.dataset.tabPanel !== tab; });
    app.querySelectorAll<HTMLAnchorElement>('[data-tab-link]').forEach(link => link.toggleAttribute('aria-current', link.dataset.tabLink === tab));
    if (updateUrl) window.history.pushState({}, '', isGenericTicker
      ? `/finance/ticker?symbol=${encodeURIComponent(ticker)}${tab === 'overview' ? '' : `&tab=${tab}`}` : tab === 'overview' ? `/finance/${ticker}` : `/finance/${ticker}?tab=${tab}`);
    if (tab === 'overview') void loadHistory(); else if (tab === 'financials') void loadFinancials(); else void loadEarnings();
  };

  app.querySelector<HTMLFormElement>('[data-company-jump]')!.addEventListener('submit', event => {
    event.preventDefault(); const input = app.querySelector<HTMLInputElement>('[data-company-jump-input]')!; const query = input.value.trim().toLowerCase();
    const match = catalog.find(item => item.symbol.toLowerCase() === query || item.name.toLowerCase() === query)
      || catalog.find(item => item.symbol.toLowerCase().startsWith(query) || item.name.toLowerCase().includes(query));
    if (match && query) window.location.href = `/finance/${match.symbol}`;
    else if (/^[a-z0-9.-]{1,10}$/.test(query)) window.location.href = `/finance/ticker?symbol=${encodeURIComponent(query.toUpperCase())}`;
    else { status.textContent = query ? 'Enter a valid U.S. ticker, or choose a listed company.' : 'Type a ticker or company name first.'; status.dataset.kind = 'error'; }
  });
  app.querySelectorAll<HTMLAnchorElement>('[data-tab-link]').forEach(link => link.addEventListener('click', event => { event.preventDefault(); showTab(link.dataset.tabLink as CompanyTab, true); }));
  window.addEventListener('popstate', () => showTab(selectedTab()));
  app.querySelectorAll<HTMLButtonElement>('[data-range]').forEach(button => button.addEventListener('click', () => { if (!currentHistory) return; activeRange = button.dataset.range as ChartRange; renderChart(currentHistory); }));
  let resizeFrame = 0;
  new ResizeObserver(() => { if (!currentHistory) return; cancelAnimationFrame(resizeFrame); resizeFrame = requestAnimationFrame(() => currentHistory && renderChart(currentHistory)); }).observe(chartWrap);
  void loadOverview(); showTab(selectedTab());
}
