using JraAgent.Core;
using JraAgent.Windows;

namespace JraAgent.Cli;

/// <summary>
/// コマンドライン（旧 cli.py の移植）。<c>jraagent --config &lt;path&gt; &lt;command&gt; [options]</c>。
/// <list type="bullet">
/// <item>verify-config: 設定の検証（秘密が無いこと・HTTPS・検証鍵）</item>
/// <item>verify-queue / status: キューの整合性・要約（読み取り専用）</item>
/// <item>diagnose [--online]: 環境・資格情報・COM 登録・ビーコン・仕様ゲートの診断（集計のみ。JV-Link は呼ばない）</item>
/// <item>run [--verbose]: 常駐（取得計画に従う。NORMAL_OK のときだけ取得・送信）</item>
/// <item>set-credential [--stdin] [--expires-at]: 資格情報の保存（トークンは標準入力から。画面・ログ・引数に出さない）</item>
/// <item>clear-credential / clear-alert &lt;name&gt; / beacon-reset --confirm: 利用者の明示操作</item>
/// <item>update check|stage|activate|rollback|status: 署名付き更新 manifest の確認・ステージング・切替・ロールバック（自動適用はしない）</item>
/// </list>
/// 設定を使わないもの: <c>jraagent worker ...</c>（COM ワーカー。監視側が起動する）、<c>jraagent poc ...</c>（実機確認。POC.md）。
/// 終了コード: 0 = 成功 / 1 = 検査で問題あり / 2 = 引数・設定・処理の誤り / 3 = 資格情報 / 4 = 多重起動。
/// </summary>
internal static class Program
{
    private const string Usage = "usage: jraagent --config <path> <verify-config|verify-queue|status|diagnose|run|set-credential|clear-credential|clear-alert|beacon-reset|update> [options]\n"
        + "       jraagent worker --backend fake|com [--script <path>]\n"
        + "       jraagent poc mock|g1|g2|g3-realtime|sleep [options]";

    private static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine(Usage);
            return 2;
        }
        try
        {
            switch (args[0])
            {
                case "worker":
                    return WorkerCommand.Run(args[1..], OperatingSystem.IsWindows() ? () => OperatingSystem.IsWindows() ? ComJvLink.Create() : throw new WorkerOpException("COM_UNAVAILABLE") : null);
                case "poc":
                    return Poc.Run(args[1..]);
                case "--config" when args.Length >= 3:
                    return RunCommand(ConfigLoader.Load(args[1]), args[2], args[3..]);
                default:
                    Console.Error.WriteLine(Usage);
                    return 2;
            }
        }
        catch (UsageException e)
        {
            Console.Error.WriteLine($"error: {e.Message}\n{Usage}");
            return 2;
        }
        catch (AgentException e)
        {
            // 例外のメッセージは固定の語彙（設定の項目名・理由コード）だけで、値・秘密を含めない
            Console.Error.WriteLine($"error: {e.GetType().Name}: {e.Message}");
            return 2;
        }
    }

    private static int RunCommand(AgentConfig cfg, string command, string[] rest)
    {
        Args A(string[] valued, string[] flags) => Args.Parse(rest, valued.ToHashSet(), flags.ToHashSet());
        return command switch
        {
            "verify-config" => Commands.VerifyConfig(cfg),
            "verify-queue" => Commands.VerifyQueue(cfg),
            "status" => Commands.Status(cfg),
            "diagnose" => Commands.Diagnose(cfg, A([], ["--online"])),
            "run" => Commands.Run(cfg, A([], ["--verbose"])),
            "set-credential" => Commands.SetCredential(cfg, A(["--expires-at"], ["--stdin"])),
            "clear-credential" => Commands.ClearCredential(cfg),
            "clear-alert" => Commands.ClearAlert(cfg, A([], [])),
            "beacon-reset" => Commands.BeaconReset(cfg, A([], ["--confirm"])),
            "update" => Commands.Update(cfg, A(["--install-root", "--manifest", "--artifact", "--version"], [])),
            _ => throw new UsageException($"未知のコマンド {command}"),
        };
    }
}
