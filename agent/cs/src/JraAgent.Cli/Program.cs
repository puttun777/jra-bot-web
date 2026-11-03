namespace JraAgent.Cli;

internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: jraagent <command> [options]");
            return 2;
        }
        return args[0] switch
        {
            "worker" => WorkerCommand.Run(args[1..]),
            _ => Usage(),
        };
    }

    private static int Usage()
    {
        Console.Error.WriteLine("usage: jraagent <command> [options]");
        return 2;
    }
}
