const marker = '<!-- rikkahub-upstream-sync-queue -->';
const upstream = 'rikkahub/rikkahub';
const branch = 'master';

module.exports = async function sync({ github, context, core }) {
  const repo = context.repo;
  const { data: repository } = await github.rest.repos.get(repo);
  if (!repository.fork || repository.parent?.full_name !== upstream) {
    throw new Error(`Expected a fork of ${upstream}; refusing to sync another source.`);
  }
  if (!repository.has_issues) {
    throw new Error('Enable Issues in repository settings before running upstream sync.');
  }

  // Look at closed issues too: reopen the same queue instead of creating duplicates.
  const issues = await github.paginate(github.rest.issues.listForRepo, {
    ...repo, state: 'all', creator: 'github-actions[bot]', per_page: 100,
  });
  const queue = issues.find(issue => !issue.pull_request && issue.body?.includes(marker));
  let result;
  try {
    // GitHub merges the fork's actual parent, preserving fork-only commits.
    // There is deliberately no force push, reset, or automatic conflict resolution.
    result = await github.rest.repos.mergeUpstream({ ...repo, branch });
  } catch (error) {
    // Auth, ruleset, workflow-permission and server errors must NOT masquerade as conflicts.
    if (error.status !== 409) throw error;
    const { data: head } = await github.rest.repos.getBranch({
      owner: 'rikkahub', repo: 'rikkahub', branch,
    });
    const sha = head.commit.sha;
    const compare = `https://github.com/${repo.owner}/${repo.repo}/compare/${branch}...rikkahub:${branch}`;
    const body = [
      marker,
      '## 本家更新の解決待ちキュー',
      '',
      `本家: https://github.com/${upstream}/tree/${branch}`,
      `検出時の本家コミット: ${sha}`,
      `取り込み先: ${repo.owner}/${repo.repo} (${branch})`,
      '',
      '競合のため今回の取り込み全体を保留しました。master の内容は変更していません。',
      'この Issue に未取り込み更新をまとめ、毎回再試行します。競合するコミットだけを飛ばす方式ではありません。',
      '',
      `[未取り込み差分を確認](${compare})`,
      '',
      '### 解決手順',
      '1. 最新の master から作業ブランチを作成します。',
      `2. git fetch https://github.com/${upstream}.git ${branch} を実行します。`,
      '3. git merge --no-ff FETCH_HEAD を実行し、競合を修正してマージコミットを作成します。',
      '4. テスト後、その作業ブランチをこのフォークの master に通常のマージで取り込みます（本家への PR は不要です）。',
      '5. 次の同期成功時にこの Issue は自動で閉じられます。手動実行でも再試行できます。',
      '',
      '### 本家の作者名義を保持すること',
      '- 本家の元コミットは SHA・Author・Committer を変更せず、master の祖先として残してください。',
      '- Squash and merge / Rebase and merge / cherry-pick は使用しないでください。',
      '- 競合修正は別のマージコミットとし、その名義には実際の解決者（自動処理なら Bot）を使用してください。',
      '- 本家の作者を競合修正コミットの作者にしたり、所有者の名義を自動で流用したりしないでください。',
      '- 取り込んだ本家の SHA を git merge-base --is-ancestor <SHA> master で確認してください（終了コード0が成功）。',
    ].join('\n');
    const title = '[upstream-sync] 本家更新の競合・解決待ち';
    if (!queue) {
      await github.rest.issues.create({ ...repo, title, body });
    } else if (queue.state !== 'open' || queue.body !== body) {
      await github.rest.issues.update({
        ...repo, issue_number: queue.number, title, body, state: 'open',
      });
    }
    core.warning('Upstream merge conflicted; master is unchanged and the conflict is queued.');
    await core.summary.addHeading('Upstream sync: queued')
      .addRaw('競合を解決待ち Issue に記録しました。master は変更していません。').write();
    return;
  }

  if (queue?.state === 'open') {
    await github.rest.issues.update({
      ...repo, issue_number: queue.number, state: 'closed', state_reason: 'completed',
    });
  }
  core.info(result.data.message);
  await core.summary.addHeading('Upstream sync: success')
    .addRaw('本家との同期が完了しました（変更なしの場合を含みます）。').write();
};