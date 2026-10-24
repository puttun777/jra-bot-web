"""例外の型。メッセージにトークン・署名・本文・レコードの内容を入れない（ログと例外から秘密を出さない）。"""

from __future__ import annotations


class AgentError(Exception):
    """エージェントの例外の基底。"""


class ConfigError(AgentError):
    """設定が不正（値は含めず、項目名だけを報告する）。"""


class SecretError(AgentError):
    """秘密の保存・読出し・復号に失敗（中身は含めない）。"""


class QueueError(AgentError):
    """ローカルキューの異常。"""


class QueueFullError(QueueError):
    """上限・ディスク容量不足のため、これ以上キューに入れられない（安全に停止する合図）。"""


class TransportError(AgentError):
    """通信の失敗（タイムアウト・接続不可・TLS）。`kind` は固定語彙。"""

    def __init__(self, kind: str) -> None:
        super().__init__(kind)
        self.kind = kind


class ProtocolError(AgentError):
    """サーバの応答が契約と違う。"""


class VerificationError(AgentError):
    """署名・整合性の検証に失敗（ビーコン・更新 manifest）。理由は固定語彙。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason
