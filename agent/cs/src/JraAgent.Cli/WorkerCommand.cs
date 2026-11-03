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
        switch (backend)
        {
            case "fake":
                return Serve(new FakeJvLink(script is null ? new FakeScript() : FakeScript.FromJson(File.ReadAllText(script))));
            case "com" when comFactory is not null && OperatingSystem.IsWindows():
                {
                    // JV-Link は ActiveX（STA が前提）。COM の生成・使用・解放は、この STA スレッドの中だけで行う
                    var rc = 2;
                    var t = new Thread(() =>
                    {
                        IJvLink? api = null;
                        try
                        {
                            api = comFactory();
                        }
                        catch (WorkerOpException e)
                        {
                            Console.Error.WriteLine($"COM を生成できない: {e.Code}"); // 以後の要求には NO_BACKEND で応答する（クラッシュにしない）
                        }
                        rc = Serve(api);
                    });
                    t.SetApartmentState(ApartmentState.STA);
                    t.Start();
                    t.Join();
                    return rc;
                }
            case "com":
                Console.Error.WriteLine("COM は Windows でだけ使える");
                return 2;
            default:
                Console.Error.WriteLine("usage: jraagent worker --backend fake|com [--script <path>]");
                return 2;
        }
    }

    private static int Serve(IJvLink? api)
    {
        // 端末の Ctrl+C は親（監視側）が受けて shutdown を送る。ワーカーは合図で直ちに終わらず、親の指示（または入力の終わり）で終わる
        // （旧版は CREATE_NEW_PROCESS_GROUP で Ctrl+C を分けていた）
        using var sigInt = System.Runtime.InteropServices.PosixSignalRegistration.Create(System.Runtime.InteropServices.PosixSignal.SIGINT, c => c.Cancel = true);
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
                api?.Close();
            }
            catch (Exception e) when (e is InvalidOperationException or WorkerOpException or System.Runtime.InteropServices.COMException)
            {
                // 終了処理の失敗はプロセスが終わるので無視する
            }
            (api as IDisposable)?.Dispose(); // COM の解放もワーカーの中で行う
        }
        return 0;
    }
}
