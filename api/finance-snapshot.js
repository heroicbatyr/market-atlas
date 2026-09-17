const crypto = require('node:crypto');

const TICKER_PATTERN = /^[A-Z][A-Z0-9.-]{0,9}$/;
const VALID_KINDS = new Set(['overview', 'history']);

function safeEqual(actual, expected) {
    const actualBuffer = Buffer.from(actual || '');
    const expectedBuffer = Buffer.from(expected || '');
    return actualBuffer.length === expectedBuffer.length
        && crypto.timingSafeEqual(actualBuffer, expectedBuffer);
}

function normalizeRequest(ticker, kind) {
    const normalizedTicker = String(ticker || '').trim().toUpperCase();
    const normalizedKind = String(kind || '').trim().toLowerCase();
    if (!TICKER_PATTERN.test(normalizedTicker) || !VALID_KINDS.has(normalizedKind)) return null;
    return { ticker: normalizedTicker, kind: normalizedKind };
}

module.exports = async function handler(req, res) {
    const { get, put } = await import('@vercel/blob');

    if (req.method === 'GET') {
        const request = normalizeRequest(req.query.ticker, req.query.kind);
        if (!request) return res.status(400).json({ message: 'Invalid finance snapshot request.' });
        try {
            const result = await get(`finance/${request.ticker}/${request.kind}.json`, {
                access: 'private',
                useCache: false
            });
            if (!result) return res.status(404).json({ message: 'No cached market data is available.' });
            const data = await new Response(result.stream).json();
            res.setHeader('Cache-Control', 'public, s-maxage=60, stale-while-revalidate=86400');
            return res.status(200).json({ ...data, stale: true });
        } catch (error) {
            console.error('Finance snapshot read failed:', error.name);
            return res.status(503).json({ message: 'Cached market data is temporarily unavailable.' });
        }
    }

    if (req.method === 'POST') {
        const secret = process.env.FINANCE_BACKUP_SECRET || '';
        const supplied = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
        if (!secret || !safeEqual(supplied, secret)) {
            return res.status(401).json({ message: 'Unauthorized.' });
        }
        let body;
        try {
            body = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
        } catch {
            return res.status(400).json({ message: "Invalid finance snapshot payload." });
        }
        const request = normalizeRequest(body?.ticker, body?.kind);
        if (!request || !body?.data || body.data.ticker !== request.ticker || !body.data.updatedAt) {
            return res.status(400).json({ message: 'Invalid finance snapshot payload.' });
        }
        try {
            await put(`finance/${request.ticker}/${request.kind}.json`, JSON.stringify(body.data), {
                access: 'private',
                allowOverwrite: true,
                contentType: 'application/json',
                cacheControlMaxAge: 60
            });
            return res.status(204).end();
        } catch (error) {
            console.error('Finance snapshot write failed:', error.name);
            return res.status(503).json({ message: 'Finance snapshot could not be saved.' });
        }
    }

    res.setHeader('Allow', 'GET, POST');
    return res.status(405).json({ message: 'Method not allowed.' });
};
