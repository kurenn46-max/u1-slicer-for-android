# NIGHT PRINT PETG開始温度の検証

## 250℃の出所

A1 mini向けのSTLでは BambuTargetedConfigResolver が開始・終了・材料切替テンプレートを渡す。設定を埋め込む3MFでは BambuImportedConfigComposer が同じテンプレートを選ぶ。BambuA1MiniMachineGcode の圧縮開始テンプレートを展開すると、固定した OrcaSlicer 2.2.4 の A1 mini 0.4 mm プロファイルと完全に一致する。

250℃は材料切替・パージ区間にある固定の M104 と M109 から生じる。印刷温度の変数を使う命令とは別に出力される。後続の M109 S235 はG-code上の指令。ノズルの実測値や、対象ファームウェアが冷却を待つかどうかの証拠にはならない。[同梱OrcaSlicerのプロファイル](https://github.com/SoftFever/OrcaSlicer/blob/2535da4cbd89430cb3f3b31f42673b98cf4bc041/resources/profiles/BBL/machine/Bambu%20Lab%20A1%20mini%200.4%20nozzle.json)

Bambu Studio v02.09.02.62 の正規テンプレートにも、A1 mini 0.4 mmのPETGで選ばれる分岐に固定250℃がある。220℃の分岐はPLAとノズル径の条件を持つ。PETGをPLAとして扱うと材料識別が変わる。新しいテンプレートをそのまま取り込むと、旧エンジンには存在しない変数とファームウェアへの前提が入る。[Bambu Studioの固定リビジョン](https://github.com/bambulab/BambuStudio/blob/23a33758933e712c6041e1f8f135f7d6b50004aa/resources/profiles/BBL/machine/Bambu%20Lab%20A1%20mini%200.4%20nozzle%20template%20machine_start_gcode.json)

SUNLUの現行通常PETG商品ページには造形用ノズル設定として240–260℃の記載がある。対象ロールの型番・ラベル・ロットは未確認で、記載はA1 miniの開始パージを認定するものではない。既存の235℃／70℃は回帰用設定。240℃／70℃は通常のノズル設定で開始指令が変わるかを調べる、オフライン比較用設定。[SUNLU通常PETG仕様](https://www.sunlu.com/jp/products/petg-3d-printing-filament)

## ソフトウェアの検証

- PETG 235℃／70℃でSTL・3MFを実JNIスライスし、材料、プリンター、G-code上のベッド温度・全ヒーター命令、SHA256、36層と形状経路を保存する。終了はA1 miniの固定終了テンプレートの境界で区別する。積層区間に温度命令がない場合、物理温度は未測定。
- STLをPETG 240℃／70℃でも実JNIスライスする。開始指令の250℃が残る場合は安全HOLDとする。現行プロファイルと監査判定を維持する。
- 実出力のコピーで積層区間以後の高温、235℃に戻す待機命令の欠落、異機種表記、対象温度命令の欠落をそれぞれ検証する。テスト用のコピーは印刷・配布しない。
- Android UIで保留理由、確認できていない実機条件、保存・共有不可の説明を日本語で確認する。JUnit XML、監査JSON、Actions Summaryを同じCI実行に保存する。

## 実機検証の前提と完了条件

1. オーナー立会いの承認を得る。A1 miniの機種・ファームウェア、ノズル径、外部スプール／AMS Liteの構成、対象ロールのSKU・ロット・仕様を記録する。
2. Bambu Studioの対応バージョンで、対象構成の正規レシピをオフライン生成して比較する。開始区間、パージ条件、最後の待機命令と終了時の温度指令を突き合わせる。メーカー確認済みの非高温レシピが見つかるまではNIGHT PRINTのHOLDを維持する。
3. 実機確認は、メーカーの手順と材料条件が確認でき、オーナーが操作する別試験で行う。開始時の温度推移、復帰の実測、初層、穴と嵌合寸法を記録し、承認条件を再評価する。外部受信アプリへの共有は別の回帰項目とする。
