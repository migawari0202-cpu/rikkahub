# 本家の自動同期

`migawari0202-cpu/rikkahub` の `master` は GitHub Actions の **Sync upstream** で
本家 `rikkahub/rikkahub` の `master` を毎時17分（UTC）に取り込みます。
PC の起動やローカルの認証情報には依存せず、GitHub の `GITHUB_TOKEN` を使用します。
Actions の画面から手動実行も可能です。定期実行は GitHub の混雑で遅れる場合があります。

## 競合キュー

- GitHub の merge-upstream API で取り込み、フォーク独自のコミットを保持します。
- 競合（HTTP 409）時はその回の取り込み全体を保留し、master を変更しません。
- `[upstream-sync] 本家更新の競合・解決待ち` Issue に未取り込み更新をまとめます。
  同じ競合で Issue やコメントを増やさず、本家の先端が変わると内容を更新します。
- 毎回再試行し、同期成功後はキューを閉じます。再び競合したら同じ Issue を再開します。
- 競合コミットを飛ばして後続だけ適用したり、片側の変更を捨てたりはしません。
  競合が解決するまでは後続の更新も保留です。
- 認証・権限・ブランチルール・通信エラーは競合扱いにせず、Actions を失敗させます。

## 作者名義と競合解決

本家のコミットは SHA・Author・Committer をそのまま保持します。競合が発生しても
元コミットの名義をフォーク所有者や Bot に書き換えません。

手動解決では作業ブランチで `git merge --no-ff FETCH_HEAD` を行い、競合修正を
別のマージコミットにしてください。master への取り込みも通常のマージを使用し、
**Squash and merge / Rebase and merge / cherry-pick は使用しないでください**。
本家の元コミットが master の祖先として残ることで、元の作者名義も履歴に残ります。
取り込んだ本家の SHA を `git merge-base --is-ancestor <SHA> master` で確認できます
（終了コード0が成功）。

マージコミット自体の名義は実際の解決者、自動処理なら Bot にします。
本家の作者に競合修正を行ったように見せたり、フォーク所有者の名義を自動流用したり
しないでください。現状の同期処理は競合を自動修正しません。この手順は Issue にも
表示しますが、手動操作のマージ方式を技術的に強制する設定ではありません。

## GitHub 設定と制約

- このフォークの Issues と Actions、および Sync upstream の定期実行が有効である必要があります。
- workflow は `contents: write` / `issues: write` を要求します。
  組織ポリシーや master の保護ルールで拒否される場合、それらの調整が必要です。
- 本家で workflow ファイルが変更された場合、GitHub の workflow 更新権限の制限により
  `GITHUB_TOKEN` で取り込めないことがあります。その場合は Actions の失敗を確認し、
  適切な権限での手動同期、または専用 GitHub App の導入が必要です。
- GitHub は公開リポジトリで60日間活動がない場合、定期実行を無効化することがあります。
  無効化された場合は GitHub の Actions 画面で再有効化してください。
- `GITHUB_TOKEN` による更新は通常、別の push 起点の workflow を起動しません。
  この同期はビルド成功を保証するものではありません。
- ローカル PC の作業ツリーは自動変更しません。必要なときに `git pull` してください。

## 検証

リポジトリのルートで `node --test .github/scripts/sync-upstream.test.cjs` を実行します。
外部ライブラリは不要です。workflow 内でも同期前に同じテストを実行します。