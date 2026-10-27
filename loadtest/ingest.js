import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const ENDPOINT_COUNT = 20;

export const options = {
    scenarios: {
        ingest: {
            executor: 'constant-arrival-rate',   // keep a fixed request rate, whatever the server does
            rate: 200,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],         // fewer than 1% errors
        http_req_duration: ['p(95)<200'],       // 95% of ingests under 200 ms
    },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
    const ids = [];
    for (let i = 0; i < ENDPOINT_COUNT; i++) {
        const res = http.post(`${BASE}/endpoints`,
            JSON.stringify({ url: 'http://localhost:8080/test/ok' }), { headers: JSON_HEADERS });
        const id = res.json('id');
        http.put(`${BASE}/endpoints/${id}/rate-limit?perSecond=1000`);
        ids.push(id);
    }
    return { ids };
}

export default function (data) {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
    const res = http.post(`${BASE}/endpoints/${id}/events`,
        JSON.stringify({ event: 'load.test', n: __ITER }),
        { headers: { ...JSON_HEADERS, 'Idempotency-Key': key } });
    check(res, { 'accepted (202)': (r) => r.status === 202 });
}