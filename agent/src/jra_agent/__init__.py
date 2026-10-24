"""JRA Bot Windows 取得エージェント。

責務の分離: 取得（jvlink/）→ キュー（queue/）→ 送信（sender）。サーバ契約は docs/CONTRACTS.md（§3・§7）。
サーバ側の署名・封筒・ACK の契約を再実装せず、定義どおりに利用する（signing.py / envelope.py）。
"""

__version__ = "0.1.0"
PROTOCOL_VERSION = 1
