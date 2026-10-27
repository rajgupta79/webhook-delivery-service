// Measures DELIVERY throughput: how fast the worker pool drains the queue.
// Phase 1: ingest a burst of N events as fast as possible.
// Phase 2: poll the dashboard until DELIVERED stops growing, compute events/sec.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const BURST = Number(__ENV.BURST || 3000);

const drainRate = new Trend('delivery_drain_rate_per_sec');
const ingested = new Counter('burst_ingested');

export const options = {
    scenarios: {
        burst: {
            executor: 'shared-iterations',
            vus: 40,
            iterations: BURST,
            maxDuration: '120s',
        },
    },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
    const res = http.post(`${BASE}/endpoints`,
        JSON.stringify({ url: `${BASE}/test/ok` }), { headers: JSON_HEADERS });
    const id = res.json('id');
    // Max allowed by EndpointSettingsController is 1000/sec.
    const rl = http.put(`${BASE}/endpoints/${id}/rate-limit?perSecond=1000`);
    console.log(`rate-limit set status=${rl.status}`);

    const before = summary();
    console.log(`endpoint=${id} deliveredBefore=${before.delivered}`);
    return { id, deliveredBefore: before.delivered, startMs: Date.now() };
}

function summary() {
    const s = http.get(`${BASE}/api/dashboard/summary`).json();
    return {
        delivered: s.byStatus.DELIVERED,
        pending: s.byStatus.PENDING,
        delivering: s.byStatus.DELIVERING,
        queueDepth: s.queueDepth,
        delayed: s.delayed,
    };
}

export default function (data) {
    const key = `drain-${__VU}-${__ITER}`;
    const res = http.post(`${BASE}/endpoints/${data.id}/events`,
        JSON.stringify({ event: 'drain', n: __ITER }),
        { headers: { ...JSON_HEADERS, 'Idempotency-Key': key } });
    check(res, { 'accepted (202)': (r) => r.status === 202 });
    ingested.add(1);
}

// Drain measurement is done separately (loadtest/measure.ps1) to avoid
// k6's 60s teardown timeout. This script only burst-ingests.
