// Unit tests for the all-endpoints catalog model (js/catalog.js): `npm test`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { catalogModel, describeSchedule, FILTERS } from '../js/catalog.js';

const sample = JSON.parse(readFileSync(new URL('../graph.sample.json', import.meta.url), 'utf8'));
const cards = m => m.groups.flatMap(g => g.cards);

test('the sample has five cards in two groups, with the board totals', () => {
  const m = catalogModel(sample);
  assert.deepEqual(m.groups.map(g => [g.owner, g.cards.length]), [['AccountController', 4], ['DormantAccountCleanup', 1]]);
  assert.deepEqual(m.totals, { entries: 5, tables: 2, topics: 1, services: 1 });
});

test('filters: writes data leaves four, has errors leaves the POST', () => {
  const all = cards(catalogModel(sample));
  assert.equal(all.filter(FILTERS.writes).length, 4);
  assert.deepEqual(all.filter(FILTERS.errors).map(c => `${c.verb} ${c.title}`), ['POST /api/accounts']);
});

test('the POST card sketches its main path and skips the error branch', () => {
  const post = cards(catalogModel(sample)).find(c => c.title === '/api/accounts' && c.verb === 'POST');
  assert.deepEqual(post.sketch, ['start', 'step', 'decision', 'step', 'sub', 'publish', 'end']);
  assert.equal(post.handler, 'createAccount()');
});

test('the scheduled job card says when it runs', () => {
  const job = cards(catalogModel(sample)).find(c => c.kind === 'scheduled');
  assert.deepEqual([job.verb, job.title, job.schedule, job.handler], ['CRON', '0 0 3 * * *', 'Runs daily at 03:00', 'purgeClosedAccounts()']);
  assert.deepEqual(job.sketch, ['clock', 'step', 'end']);
});

test('schedules in plain English', () => {
  const cases = {
    'cron=0 0 3 * * *': 'Runs daily at 03:00',
    'cron=0 30 9 * * MON-FRI': 'Runs on weekdays at 09:30',
    'cron=0 0 6 * * SUN': 'Runs on Sundays at 06:00',
    'cron=0 0 1 1 * *': 'Runs monthly on day 1 at 01:00',
    'cron=0 15 * * * *': 'Runs hourly at :15',
    'cron=0 */5 * * * *': 'Runs every 5 minutes',
    'cron=0 0 */2 * * *': 'Runs every 2 hours',
    'cron=*/30 * * * * *': 'Runs every 30 seconds',
    'cron=0 3 * * *': 'Runs daily at 03:00',
    'cron=@hourly': 'Runs hourly',
    'cron=0 0 3 L * ?': 'Runs on cron 0 0 3 L * ?',
    'fixedRate=60000': 'Runs every minute',
    'fixedRate=5000': 'Runs every 5 seconds',
    'fixedDelay=7200000': 'Runs 2 hours after each run ends',
    'fixedRate=${poll.rate}': 'Runs at a fixed rate (${poll.rate})',
    'every=10s': 'Runs every 10s',
    'quartz:NightlyJob': 'Quartz job NightlyJob',
    '': 'Scheduled',
  };
  for (const [input, want] of Object.entries(cases)) assert.equal(describeSchedule(input), want, input);
});
