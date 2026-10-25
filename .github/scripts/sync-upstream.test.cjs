const { test } = require('node:test');
const assert = require('node:assert/strict');
const sync = require('./sync-upstream.cjs');

function fixture({ status, issues = [], hasIssues = true, parent = 'rikkahub/rikkahub' } = {}) {
  const calls = [];
  const record = name => async args => { calls.push({ name, args }); };
  const summary = {
    addHeading() { return this; }, addRaw() { return this; }, write: record('summary'),
  };
  return {
    calls,
    context: { repo: { owner: 'migawari0202-cpu', repo: 'rikkahub' } },
    core: { info() {}, warning: record('warning'), summary },
    github: {
      paginate: async () => issues,
      rest: {
        repos: {
          get: async () => ({ data: { fork: true, parent: { full_name: parent }, has_issues: hasIssues } }),
          getBranch: async () => ({ data: { commit: { sha: 'abc123' } } }),
          mergeUpstream: async args => {
            calls.push({ name: 'merge', args });
            if (status) throw Object.assign(new Error('API error'), { status });
            return { data: { message: 'Successfully merged' } };
          },
        },
        issues: { listForRepo() {}, create: record('create'), update: record('update') },
      },
    },
  };
}

test('merges master using the parent API without creating an issue', async () => {
  const f = fixture();
  await sync(f);
  assert.deepEqual(f.calls.find(c => c.name === 'merge').args, {
    owner: 'migawari0202-cpu', repo: 'rikkahub', branch: 'master',
  });
  assert.equal(f.calls.some(c => c.name === 'create' || c.name === 'update'), false);
});

test('409 creates a conflict queue and completes normally', async () => {
  const f = fixture({ status: 409 });
  await sync(f);
  const issue = f.calls.find(c => c.name === 'create').args;
  assert.match(issue.body, /abc123/);
  assert.match(issue.body, /git merge --no-ff FETCH_HEAD/);
  assert.match(issue.body, /SHA・Author・Committer/);
  assert.match(issue.body, /Squash and merge \/ Rebase and merge \/ cherry-pick は使用しない/);
  assert.match(issue.body, /git merge-base --is-ancestor/);
  assert.equal(f.calls.filter(c => c.name === 'merge').length, 1);
});

test('repeated conflict is deduplicated; closed queue reopens; new head updates queue', async () => {
  const first = fixture({ status: 409 });
  await sync(first);
  const body = first.calls.find(c => c.name === 'create').args.body;
  for (const [state, oldBody, updates] of [
    ['open', body, 0], ['closed', body, 1], ['open', body.replace('abc123', 'older'), 1],
  ]) {
    const f = fixture({ status: 409, issues: [{ number: 7, state, body: oldBody }] });
    await sync(f);
    assert.equal(f.calls.filter(c => c.name === 'create').length, 0);
    assert.equal(f.calls.filter(c => c.name === 'update').length, updates);
    if (updates) assert.equal(f.calls.find(c => c.name === 'update').args.state, 'open');
  }
});

test('success closes an open queue but leaves unrelated issues alone', async () => {
  const f = fixture({ issues: [
    { number: 3, state: 'open', body: 'unrelated' },
    { number: 7, state: 'open', body: '<!-- rikkahub-upstream-sync-queue -->' },
  ] });
  await sync(f);
  const update = f.calls.find(c => c.name === 'update').args;
  assert.equal(update.issue_number, 7);
  assert.equal(update.state, 'closed');
});

test('non-conflict API failures fail visibly, never queue or close issues', async () => {
  for (const status of [401, 403, 404, 422, 429, 500]) {
    const f = fixture({ status });
    await assert.rejects(sync(f), { status });
    assert.equal(f.calls.some(c => c.name === 'create' || c.name === 'update'), false);
  }
});

test('queue write failure is not ignored', async () => {
  const f = fixture({ status: 409 });
  f.github.rest.issues.create = async () => { throw new Error('Issue write failed'); };
  await assert.rejects(sync(f), /Issue write failed/);
});

test('wrong parent and disabled Issues are rejected before merging', async () => {
  for (const options of [{ parent: 'other/repo' }, { hasIssues: false }]) {
    const f = fixture(options);
    await assert.rejects(sync(f));
    assert.equal(f.calls.some(c => c.name === 'merge'), false);
  }
});

test('normal conflict-resolution merge preserves upstream commit identity and ancestry', () => {
  const { execFileSync, spawnSync } = require('node:child_process');
  const { mkdtempSync, writeFileSync, rmSync } = require('node:fs');
  const { tmpdir } = require('node:os');
  const { join } = require('node:path');
  const cwd = mkdtempSync(join(tmpdir(), 'rikkahub-sync-test-'));
  const env = { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: join(cwd, 'no-global-config') };
  for (const key of ['GIT_AUTHOR_NAME', 'GIT_AUTHOR_EMAIL', 'GIT_COMMITTER_NAME', 'GIT_COMMITTER_EMAIL']) {
    delete env[key];
  }
  const git = (...args) => execFileSync('git', args, { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const write = text => writeFileSync(join(cwd, 'shared.txt'), text);
  try {
    git('init', '--initial-branch=master');
    git('config', 'user.name', 'Original Author');
    git('config', 'user.email', 'original@example.invalid');
    write('base\n');
    git('add', 'shared.txt');
    git('commit', '-m', 'base');
    git('checkout', '-b', 'upstream');
    write('upstream change\n');
    git('commit', '-am', 'upstream change');
    const sha = git('rev-parse', 'HEAD');
    const identity = git('show', '-s', '--format=%H %an %ae %cn %ce', sha);
    git('checkout', 'master');
    git('config', 'user.name', 'Resolution Bot');
    git('config', 'user.email', 'bot@example.invalid');
    write('fork change\n');
    git('commit', '-am', 'fork change');
    const merge = spawnSync('git', ['merge', '--no-ff', 'upstream'], { cwd, env, encoding: 'utf8' });
    assert.equal(merge.status, 1);
    assert.equal(git('diff', '--name-only', '--diff-filter=U'), 'shared.txt');
    write('upstream change\nfork change\n');
    git('add', 'shared.txt');
    git('commit', '-m', 'resolve conflict, retaining both histories');
    assert.equal(git('rev-parse', 'HEAD^2'), sha);
    assert.equal(git('show', '-s', '--format=%H %an %ae %cn %ce', sha), identity);
    git('merge-base', '--is-ancestor', sha, 'master');
    assert.equal(git('show', '-s', '--format=%an <%ae>', 'HEAD'), 'Resolution Bot <bot@example.invalid>');
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});