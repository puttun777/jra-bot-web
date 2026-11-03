namespace JraAgent.Core;

/// <summary>エージェントの例外の基底。メッセージにトークン・署名・本文・レコードの内容を入れない（旧 Python の errors.py と同じ方針）。</summary>
public class AgentException : Exception
{
    public AgentException() { }

    public AgentException(string message) : base(message) { }

    public AgentException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>設定が不正（値は含めず、項目名だけを報告する）。</summary>
public sealed class ConfigException(string message) : AgentException(message);

/// <summary>秘密の保存・読出し・復号に失敗（中身は含めない）。</summary>
public sealed class SecretException(string message) : AgentException(message);

/// <summary>ローカルキューの異常。</summary>
public class QueueException(string message) : AgentException(message);

/// <summary>上限・ディスク容量不足のため、これ以上キューに入れられない（安全に停止する合図）。</summary>
public sealed class QueueFullException(string message) : QueueException(message);

/// <summary>通信の失敗（タイムアウト・接続不可・TLS）。<see cref="Kind"/> は固定語彙。</summary>
public sealed class TransportException(string kind) : AgentException(kind)
{
    public string Kind { get; } = kind;
}

/// <summary>署名・整合性の検証に失敗（ビーコン・更新 manifest）。理由は固定語彙。</summary>
public sealed class VerificationException(string reason) : AgentException(reason)
{
    public string Reason { get; } = reason;
}
