export {};
type StockOverview = {
  ticker: string; currency: string | null; marketCap: number | null; peRatio: number | null;
  eps: number | null; week52High: number | null; week52Low: number | null; netMargin: number | null;
};
type PricePoint = { date: string; close: number };
type StockHistory = { ticker: string; currency: string | null; range: string; points: PricePoint[] };
type AnnualFinancial = { date: string; revenue: number | null; netIncome: number | null; freeCashFlow: number | null; netMargin: number | null };
type CompanyFinancials = { symbol: string; currency: string | null; annual: AnnualFinancial[]; cashAndEquivalents: number | null; totalDebt: number | null; stale: boolean };
type CompareRange = '1m' | '6m' | '1y' | '2y' | '5y';

const app = document.querySelector<HTMLElement>('[data-compare-app]');
if (app) {
  const apiBase = app.dataset.apiBase?.replace(/\/$/, '') || 'https://server.batyrbek.com';
  const firstSelect = app.querySelector<HTMLInputElement>('[data-first-symbol]')!;
  const secondSelect = app.querySelector<HTMLInputElement>('[data-second-symbol]')!;
  const status = app.querySelector<HTMLElement>('[data-compare-status]')!;
  const results = app.querySelector<HTMLElement>('[data-compare-results]')!;
  const table = app.querySelector<HTMLElement>('[data-comparison-table]')!;
  const chartWrap = app.querySelector<HTMLElement>('[data-compare-chart-wrap]')!;
  let activeRange: CompareRange = '1y';
  let overviews: [StockOverview, StockOverview] | null = null;
  let histories: [StockHistory, StockHistory] | null = null;
  let currentSymbols: [string, string] | null = null;
  let selectedIndex = 0;
  let loadSequence = 0;

  const params = new URLSearchParams(window.location.search);
  const requested = (params.get('symbols') || 'NVDA,AMD').toUpperCase().split(',');
  if (/^[A-Z][A-Z0-9.-]{0,9}$/.test(requested[0] || '')) firstSelect.value = requested[0];
  if (/^[A-Z][A-Z0-9.-]{0,9}$/.test(requested[1] || '')) secondSelect.value = requested[1];

  const number = (value: number | null, digits = 2) => value == null ? '-' : new Intl.NumberFormat('en-US', { maximumFractionDigits: digits }).format(value);
  const money = (value: number | null, currency: string | null, compact = false) => {
    if (value == null) return '-';
    try { return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency || 'USD', notation: compact ? 'compact' : 'standard', maximumFractionDigits: 2 }).format(value); }
    catch { return number(value); }
  };
  const setText = (selector: string, value: string) => { const element = app.querySelector<HTMLElement>(selector); if (element) element.textContent = value; };
  const fetchJson = async <T>(path: string) => { const response = await fetch(apiBase + path, { headers: { Accept: 'application/json' } }); if (!response.ok) throw new Error('Market data is temporarily unavailable.'); return response.json() as Promise<T>; };
  const pointDate = (point: PricePoint) => new Date(`${point.date}T00:00:00Z`);
  const startFor = (range: CompareRange, end: Date) => { const start = new Date(end); if (range === '1m') start.setUTCMonth(start.getUTCMonth() - 1); if (range === '6m') start.setUTCMonth(start.getUTCMonth() - 6); if (range === '1y') start.setUTCFullYear(start.getUTCFullYear() - 1); if (range === '2y') start.setUTCFullYear(start.getUTCFullYear() - 2); if (range === '5y') start.setUTCFullYear(start.getUTCFullYear() - 5); return start; };
  const visiblePoints = (history: StockHistory, range = activeRange) => {
    const clean = history.points.filter(point => Number.isFinite(point.close));
    if (!clean.length) return clean;
    const start = startFor(range, pointDate(clean.at(-1)!));
    const visible = clean.filter(point => pointDate(point) >= start);
    const previous = clean.filter(point => pointDate(point) < start).at(-1);
    return previous ? [previous, ...visible] : visible;
  };
  const performance = (points: PricePoint[]) => points.length < 2 ? null : (points.at(-1)!.close / points[0].close - 1) * 100;
  const formatReturn = (value: number | null) => value == null ? '-' : `${value > 0 ? '+' : ''}${number(value)}%`;
  const svgNode = <K extends keyof SVGElementTagNameMap>(name: K) => document.createElementNS('http://www.w3.org/2000/svg', name);
  const nearestPoint = (points: PricePoint[], time: number) => points.reduce((best, point) => Math.abs(pointDate(point).getTime() - time) < Math.abs(pointDate(best).getTime() - time) ? point : best, points[0]);

  const renderChart = () => {
    if (!histories || !overviews) return;
    const series = histories.map(history => visiblePoints(history)) as [PricePoint[], PricePoint[]];
    const svg = app.querySelector<SVGSVGElement>('[data-compare-chart]')!;
    const tooltip = app.querySelector<HTMLElement>('[data-compare-tooltip]')!;
    svg.replaceChildren(); tooltip.hidden = true;
    app.querySelectorAll<HTMLButtonElement>('[data-compare-range]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.compareRange === activeRange)));
    const returns = series.map(performance); setText('[data-first-return]', formatReturn(returns[0])); setText('[data-second-return]', formatReturn(returns[1]));
    if (series.some(points => points.length < 2)) { setText('[data-chart-message]', 'Not enough history is available for this period.'); return; }
    setText('[data-chart-message]', '');
    const normalized = series.map(points => points.map(point => ({ source: point, date: pointDate(point), value: point.close / points[0].close * 100 }))) as [{ source: PricePoint; date: Date; value: number }[], { source: PricePoint; date: Date; value: number }[]];
    const all = normalized.flat(); const minDate = Math.min(...all.map(point => point.date.getTime())); const maxDate = Math.max(...all.map(point => point.date.getTime()));
    const rawMin = Math.min(...all.map(point => point.value)); const rawMax = Math.max(...all.map(point => point.value)); const padding = (rawMax - rawMin || 5) * .1; const min = rawMin - padding; const max = rawMax + padding;
    const width = Math.max(svg.clientWidth, 320); const height = Math.max(svg.clientHeight, 280); const plot = { left: 8, top: 15, right: width - 58, bottom: height - 32 };
    const xFor = (time: number) => plot.left + (time - minDate) / (maxDate - minDate || 1) * (plot.right - plot.left);
    const yFor = (value: number) => plot.top + (max - value) / (max - min || 1) * (plot.bottom - plot.top);
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    for (let index = 0; index < 4; index += 1) { const y = plot.top + index / 3 * (plot.bottom - plot.top); const line = svgNode('line'); line.setAttribute('x1', String(plot.left)); line.setAttribute('x2', String(plot.right)); line.setAttribute('y1', String(y)); line.setAttribute('y2', String(y)); line.setAttribute('class', 'compare-grid'); svg.append(line); const label = svgNode('text'); label.textContent = number(max - index / 3 * (max - min), 1); label.setAttribute('x', String(width - 4)); label.setAttribute('y', String(y + 3)); label.setAttribute('text-anchor', 'end'); label.setAttribute('class', 'compare-axis'); svg.append(label); }
    normalized.forEach((points, seriesIndex) => { const path = points.map((point, index) => `${index ? 'L' : 'M'} ${xFor(point.date.getTime()).toFixed(2)} ${yFor(point.value).toFixed(2)}`).join(' '); const line = svgNode('path'); line.setAttribute('d', path); line.setAttribute('class', seriesIndex === 0 ? 'compare-line-first' : 'compare-line-second'); svg.append(line); });
    const dateFormat = new Intl.DateTimeFormat('en', { month: 'short', year: '2-digit', timeZone: 'UTC' });
    [0, .5, 1].forEach((ratio, index) => { const label = svgNode('text'); label.textContent = dateFormat.format(new Date(minDate + ratio * (maxDate - minDate))); label.setAttribute('x', String(plot.left + ratio * (plot.right - plot.left))); label.setAttribute('y', String(height - 7)); label.setAttribute('text-anchor', index === 0 ? 'start' : index === 2 ? 'end' : 'middle'); label.setAttribute('class', 'compare-axis'); svg.append(label); });
    const crosshair = svgNode('line'); crosshair.setAttribute('y1', String(plot.top)); crosshair.setAttribute('y2', String(plot.bottom)); crosshair.setAttribute('class', 'compare-crosshair'); crosshair.setAttribute('visibility', 'hidden');
    const firstDot = svgNode('circle'); firstDot.setAttribute('r', '4'); firstDot.setAttribute('class', 'compare-dot-first'); firstDot.setAttribute('visibility', 'hidden');
    const secondDot = svgNode('circle'); secondDot.setAttribute('r', '4'); secondDot.setAttribute('class', 'compare-dot-second'); secondDot.setAttribute('visibility', 'hidden'); svg.append(crosshair, firstDot, secondDot);
    const anchors = series[0].length >= series[1].length ? series[0] : series[1]; if (selectedIndex <= 0 || selectedIndex >= anchors.length) selectedIndex = anchors.length - 1;
    const showAt = (targetTime: number) => {
      const rawPoints = series.map(points => nearestPoint(points, targetTime)) as [PricePoint, PricePoint];
      const normalizedValues = rawPoints.map((point, index) => point.close / series[index][0].close * 100) as [number, number];
      const crossX = xFor(targetTime); crosshair.removeAttribute('visibility'); firstDot.removeAttribute('visibility'); secondDot.removeAttribute('visibility'); tooltip.hidden = false;
      crosshair.setAttribute('x1', String(crossX)); crosshair.setAttribute('x2', String(crossX));
      [firstDot, secondDot].forEach((dot, index) => { dot.setAttribute('cx', String(xFor(pointDate(rawPoints[index]).getTime()))); dot.setAttribute('cy', String(yFor(normalizedValues[index]))); });
      const displayDate = new Date(targetTime); setText('[data-tooltip-date]', new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(displayDate));
      setText('[data-tooltip-first-label]', overviews![0].ticker); setText('[data-tooltip-second-label]', overviews![1].ticker);
      setText('[data-tooltip-first-price]', money(rawPoints[0].close, histories![0].currency || overviews![0].currency)); setText('[data-tooltip-second-price]', money(rawPoints[1].close, histories![1].currency || overviews![1].currency));
      const tooltipWidth = tooltip.offsetWidth || 210; tooltip.style.left = `${Math.max(0, Math.min(width - tooltipWidth, crossX - tooltipWidth / 2))}px`; tooltip.style.top = `${Math.max(4, Math.min(yFor(normalizedValues[0]), yFor(normalizedValues[1])) - 108)}px`;
    };
    const hide = () => { crosshair.setAttribute('visibility', 'hidden'); firstDot.setAttribute('visibility', 'hidden'); secondDot.setAttribute('visibility', 'hidden'); tooltip.hidden = true; };
    chartWrap.onpointermove = event => { const bounds = chartWrap.getBoundingClientRect(); const x = Math.max(plot.left, Math.min(plot.right, event.clientX - bounds.left)); const time = minDate + (x - plot.left) / (plot.right - plot.left) * (maxDate - minDate); selectedIndex = anchors.reduce((best, point, index) => Math.abs(pointDate(point).getTime() - time) < Math.abs(pointDate(anchors[best]).getTime() - time) ? index : best, 0); showAt(pointDate(anchors[selectedIndex]).getTime()); };
    chartWrap.onpointerleave = hide;
    chartWrap.onkeydown = event => { if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return; event.preventDefault(); selectedIndex = Math.max(0, Math.min(anchors.length - 1, selectedIndex + (event.key === 'ArrowRight' ? 1 : -1))); showAt(pointDate(anchors[selectedIndex]).getTime()); };
  };

  const renderCoreTable = () => {
    if (!overviews || !histories) return;
    const [first, second] = overviews; setText('[data-table-first]', first.ticker); setText('[data-table-second]', second.ticker);
    const rows: Array<[string, (stock: StockOverview) => string]> = [['market-cap', stock => money(stock.marketCap, stock.currency, true)], ['pe', stock => number(stock.peRatio)], ['eps', stock => money(stock.eps, stock.currency)], ['low', stock => money(stock.week52Low, stock.currency)], ['high', stock => money(stock.week52High, stock.currency)]];
    rows.forEach(([key, format]) => { setText(`[data-first-${key}]`, format(first)); setText(`[data-second-${key}]`, format(second)); });
    (['1m', '6m', '1y'] as CompareRange[]).forEach(range => histories!.forEach((history, index) => setText(`[data-${index ? 'second' : 'first'}-return-${range}]`, formatReturn(performance(visiblePoints(history, range))))));
    const longRange: CompareRange = histories.some(history => history.range === '2y') ? '2y' : '5y';
    setText('[data-long-return-label]', `${longRange === '2y' ? '2' : '5'} year return`);
    histories.forEach((history, index) => setText(`[data-${index ? 'second' : 'first'}-return-long]`, formatReturn(performance(visiblePoints(history, longRange)))));
    setText('[data-first-margin]', first.netMargin == null ? '-' : `${number(first.netMargin * 100, 1)}%`); setText('[data-second-margin]', second.netMargin == null ? '-' : `${number(second.netMargin * 100, 1)}%`);
  };
  const renderFinancialTable = (financials: [CompanyFinancials, CompanyFinancials]) => {
    financials.forEach((data, index) => { const side = index ? 'second' : 'first'; const latest = [...data.annual].sort((a, b) => a.date.localeCompare(b.date)).at(-1); setText(`[data-${side}-revenue]`, money(latest?.revenue ?? null, data.currency, true)); setText(`[data-${side}-net-income]`, money(latest?.netIncome ?? null, data.currency, true)); setText(`[data-${side}-fcf]`, money(latest?.freeCashFlow ?? null, data.currency, true)); setText(`[data-${side}-cash]`, money(data.cashAndEquivalents, data.currency, true)); setText(`[data-${side}-debt]`, money(data.totalDebt, data.currency, true)); if (latest?.netMargin != null) setText(`[data-${side}-margin]`, `${number(latest.netMargin * 100, 1)}%`); });
  };
  const loadFinancials = async (first: string, second: string, sequence: number) => {
    const note = app.querySelector<HTMLElement>('[data-financial-compare-status]')!; note.textContent = 'Loading annual statement comparison…';
    try { const data = await Promise.all([fetchJson<CompanyFinancials>(`/api/stocks/${first}/financials`), fetchJson<CompanyFinancials>(`/api/stocks/${second}/financials`)]) as [CompanyFinancials, CompanyFinancials]; if (sequence !== loadSequence) return; renderFinancialTable(data); note.textContent = data.some(item => item.stale) ? 'Showing the latest cached annual statements.' : 'Annual statement data is cached for seven days.'; }
    catch { if (sequence === loadSequence) { note.textContent = 'Annual statement metrics are temporarily unavailable; market comparison remains available.'; note.dataset.kind = 'error'; } }
  };
  const load = async () => {
    const sequence = ++loadSequence; const first = firstSelect.value.trim().toUpperCase(); const second = secondSelect.value.trim().toUpperCase();
    if (![first, second].every(symbol => /^[A-Z][A-Z0-9.-]{0,9}$/.test(symbol))) { status.textContent = 'Enter two valid ticker symbols.'; status.dataset.kind = 'error'; return; }
    if (first === second) { status.textContent = 'Choose two different companies.'; status.dataset.kind = 'error'; return; }
    status.textContent = `Loading ${first} and ${second}…`; status.dataset.kind = 'loading';
    try {
      const core = await Promise.all([fetchJson<StockOverview>(`/api/stocks/${first}`), fetchJson<StockOverview>(`/api/stocks/${second}`), fetchJson<StockHistory>(`/api/stocks/${first}/history?range=5y`), fetchJson<StockHistory>(`/api/stocks/${second}/history?range=5y`)]);
      if (sequence !== loadSequence) return;
      overviews = [core[0], core[1]]; histories = [core[2], core[3]]; currentSymbols = [first, second]; selectedIndex = 0;
      const limitedHistory = histories.some(history => history.range === '2y');
      const twoYearButton = app.querySelector<HTMLButtonElement>('[data-compare-range="2y"]')!;
      const fiveYearButton = app.querySelector<HTMLButtonElement>('[data-compare-range="5y"]')!;
      twoYearButton.hidden = !limitedHistory; fiveYearButton.hidden = limitedHistory;
      if (limitedHistory && activeRange === '5y') activeRange = '2y';
      setText('[data-compare-title]', `${first} vs ${second}`); setText('[data-first-label]', first); setText('[data-second-label]', second); window.history.replaceState({}, '', `/finance/compare?symbols=${first},${second}`);
      renderChart(); renderCoreTable(); results.hidden = false; table.hidden = false; status.textContent = `${first} and ${second} comparison loaded.`; status.dataset.kind = 'success';
      const financialStatus = app.querySelector<HTMLElement>("[data-financial-compare-status]")!;
      financialStatus.textContent = "Annual statement metrics are optional and cached for seven days.";
      delete financialStatus.dataset.kind;
    } catch { if (sequence === loadSequence) { status.textContent = 'Comparison data is temporarily unavailable. Your previous result is still shown.'; status.dataset.kind = 'error'; } }
  };

  app.querySelector<HTMLFormElement>('[data-compare-form]')!.addEventListener('submit', event => { event.preventDefault(); void load(); });
  app.querySelector<HTMLButtonElement>("[data-load-financials]")!.addEventListener("click", () => {
    if (!currentSymbols) return;
    const button = app.querySelector<HTMLButtonElement>("[data-load-financials]")!;
    button.disabled = true;
    void loadFinancials(currentSymbols[0], currentSymbols[1], loadSequence).finally(() => { button.disabled = false; });
  });
  app.querySelectorAll<HTMLButtonElement>('[data-compare-range]').forEach(button => button.addEventListener('click', () => { activeRange = button.dataset.compareRange as CompareRange; selectedIndex = 0; renderChart(); }));
  new ResizeObserver(() => { if (histories) renderChart(); }).observe(chartWrap);
  void load();
}
