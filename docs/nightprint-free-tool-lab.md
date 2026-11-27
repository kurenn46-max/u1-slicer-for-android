# NIGHT PRINT 無料技術実験室 — actionlint 第1段階

## 目的
Codexの再開後に進めるPR #9 (PETG 250℃とAndroid実機相当テスト)を**変更せず**、GitHub ActionsのYAMLを低コストで先に静的点検する。製品・G-code・実機の安全性を保証するものではない。

## 試験対象と固定条件
- 変更は独立PRのみ。元ブランチ: `feature/nightprint-genesis-android16-qa` (PR #6)。
- ツール: [rhysd/actionlint](https://github.com/rhysd/actionlint), **v1.7.11 固定**。
- 実行: `go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.11`、`GOSUMDB=sum.golang.org`でGoモジュールの検証を使用。
- 入力: 現在のリポジトリ内 `.github/workflows` のワークフローすべて。既存のCI結果を改変しない。
- 出力: Actions Summary + `nightprint-actionlint-report` (ツール版、原本の検出結果、終了コード、SHA)。
- 安全策: `permissions: contents: read`、外部AI APIキー・secretなし、Submodule取得なし、APKビルドなし、Android起動なし、プリンター通信なし。
- 成功条件: 固定版のツールを取得して解析を最後まで完走し、exit 0。**exit 0はActions YAMLの静的検査が通っただけでアプリ製品の合格ではない**。
- エラー時: 失敗ログを保存して独立分類する。既存のワークフローを無条件に書き換えて通すことは禁止。

## 比較と判断
1. **actionlint** はGitHubのワークフロー文法・式・利用方法を点検する。低コストでの導入実証として最優先。
2. **Maestro** は画面の実操作テストとして有望だが、Androidエミュレーターと追加ランタイムを要する。現在の既存UI 8件と比較するまでは未導入。
3. **mobsfscan** はコード静的セキュリティ検査の候補。既存CodeQLとの重複と誤検出率を調べるまでは未導入。
4. **OpenHands** は自律開発の実行系候補。別途モデル/実行環境が必要となるため、自動導入しない。

## 無課金方針
オープンソースと標準GitHub Actionsランナーだけで追加有料APIは利用しない。ただしGitHubリポジトリ種別・利用規約によって実行枠や課金条件が異なる場合があるため、料金無条件ゼロとは断定しない。

## 隔離と撤回
このPRをマージしなければ既存環境への影響なし。失敗したら本PRを閉じるだけで取り下げられる。開発中のPR #9、NIGHT CORE #8、mainやプリンターへの変更は禁止。
