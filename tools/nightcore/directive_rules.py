"""Reviewed NIGHT PRINT recommendations. CI log text is never rendered as instructions."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Rule:
    category: str
    confidence: str
    cause: str
    recommendation: str
    tests: tuple[str, ...]
    completion: str
    unverified: str


UNKNOWN = Rule(
    "unclassified", "low",
    "現在のCIは失敗していますが、失敗原因を特定できる証拠が不足しています。",
    "最初に失敗したステップと終了コードを調べ、再現条件を記録してください。原因の確定までソースは編集しないでください。",
    ("同じヘッドとベースの失敗ステップ、終了コード、既存の診断成果物を照合する",),
    "一次証拠を添えて分類を更新する。調査だけでCI合格とは判定しない。",
    "根本原因と修復可否。",
)

RULES = {
    "stale_arm64_jni": Rule(
        "build_mismatch", "high",
        "C++変更に対し、同梱ARM64 JNIが更新されていないと現行のリリースゲートが判定しました。",
        "対象のC++差分とエンジン・NDKの固定値を比較してください。対象リビジョンのRelease JNIを再ビルドし、由来を検証したバイナリを使ってください。",
        ("NDK 26.1.10909125、arm64-v8a、Release、16 KiB ELFアラインメント、JNIシンボル一致、SHA256を確認する",
         "リリースゲートとJVM回帰を実行し、同じJNIを含むAPKでSTLと3MFのネイティブスライスと監査を通す"),
        "対象のソースとAPK内JNIの対応が追跡でき、変更後の同一リビジョンでリリースゲートとAndroidネイティブ監査が合格する。",
        "再ビルド成果物の出所と、派生PRごとのC++差分。",
    ),
    "maven_central_rate_limit": Rule(
        "transient_external", "high",
        "Gradleの依存取得に対しMaven CentralがHTTP 429を返しました。",
        "固定済み依存関係とGradleキャッシュを点検し、重複取得を抑えてください。429の解消を確認できた場合だけ対象CIを1回再検証してください。",
        ("固定済み依存を保って失敗したGradle工程を再検証する",
         "再検証後のAPKを主張する場合は既存の全ゲートとAndroidテストを確認する"),
        "同じ入力で依存取得が完了し、対象の検証工程が通過する。再度429ならログを保存して保留する。",
        "Maven Centralの現在の応答と、既存キャッシュの利用可否。",
    ),
    "adb_disconnect": Rule(
        "test_environment", "high",
        "Android実行中にADBの接続エラーが記録されました。併発したテスト失敗の原因は別途確認が必要です。",
        "端末状態、失敗ステップ、logcat、接続タイムアウトを確認し、現在のコミットで再現した場合にだけCI側を修正してください。",
        ("JNIとUIの既存テストを省略せず実行し、JUnitとADB診断を保存する",),
        "実行が切断やタイムアウトで中断せず、既存のテスト件数と合否を確認できる。",
        "切断の発生要因と個別UI失敗への影響。",
    ),
    "project_assertion_failure": Rule(
        "source_defect", "medium",
        "プロジェクトのコードを通るアサーション失敗です。実装とテストのどちらに原因があるかは未確定です。",
        "アサーションの期待値と再現入力を確認し、原因を再現できた場合だけ最小限の修正を提案してください。",
        ("失敗を再現するテストと関連する回帰テストを変更後のコミットで実行する",),
        "修正前に再現し、期待値を弱めず修正後に再現テストと回帰テストが合格する。",
        "実装とテストのどちらに原因があるか。",
    ),
}

# Historical baseline is kept separate from active tasks. CI re-reads the original job.
QA4_ADB_HISTORY = {
    "pr": 6, "commit_sha": "0e8bbf5acc09052bad44b58bbe0171d03e491ede",
    "run_id": 45892458208, "job_id": 140158278755, "kind": "adb_disconnect",
}
