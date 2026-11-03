using JraAgent.Core;

namespace JraAgent.Cli;

/// <summary>
/// COM ワーカー（子プロセス）の入口: <c>jraagent worker --backend fake --script &lt;path&gt;</c> / <c>--backend com</c>（Windows・利用者が許可したときだけ）。
/// stdout は JSON 行のプロトコル専用。うっかりの出力でプロトコルを壊さないよう、Console.Out は stderr へ向ける。
/// </summary>
internal static class WorkerCommand
{
    public static int Run(IReadOnlyList<string> args, Func<IJvLink>? comFactory = null)
    {
        string? backend = null;
        string? script = null;
        for (var i = 0; i < args.Count; i++)
        {
            switch (args[i])
            {
                case "--backend" when i + 1 < args.Count:
                    backend = args[++i];
                    break;
                case "--script" when i + 1 < args.Count:
                    script = args[++i];
                    break;
                default:
                    Console.Error.WriteLine("usage: jraagent worker --backend fake|com [--script <path>]");
                    return 2;
            }
        }
        IJvLink api;
        switch (backend)
        {
            case "fake":
                api = new FakeJvLink(script is null ? new FakeScript() : FakeScript.FromJson(File.ReadAllText(script)));
                break;
            case "com" when comFactory is not null:
                api = comFactory();
                break;
            case "com":
                Console.Error.WriteLine("COM は Windows でだけ使える");
                return 2;
            default:
                Console.Error.WriteLine("usage: jraagent worker --backend fake|com [--script <path>]");
                return 2;
        }
        var stdout = Console.OpenStandardOutput();
        Console.SetOut(Console.Error);
        try
        {
            WorkerServer.Serve(api, Console.OpenStandardInput(), stdout);
        }
        finally
        {
            try
            {
                api.Close();
            }
            catch (Exception e) when (e is InvalidOperationException or System.Runtime.InteropServices.COMException)
            {
                // 終了処理の失敗はプロセスが終わるので無視する
            }
            (api as IDisposable)?.Dispose(); // COM の解放もワーカーの中で行う
        }
        return 0;
    }
}
