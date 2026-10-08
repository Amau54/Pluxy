using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class ServerController : IDisposable {
        private Process bootstrap;
        private Process server;
        public string Address { get; private set; }
        public bool Owned { get { return server != null && !server.HasExited; } }
        private int Port() {
            try { var cfg=Native.Object(Native.Json.DeserializeObject(File.ReadAllText(Path.Combine(Native.Data,"config.json")))); int port=(int)Native.Number(Native.Object(cfg["server"]),"port",8420); return port>=1 && port<=65535?port:8420; }
            catch { return 8420; }
        }
        private async Task<bool> Ready() {
            try { using(var http=new HttpClient(new HttpClientHandler { UseProxy=false }) { Timeout=TimeSpan.FromSeconds(1) }) {
                var result=Native.Object(Native.Json.DeserializeObject(await http.GetStringAsync(Address+"/health")));
                return Native.Text(result,"app")=="pluxy" && Native.Text(result,"status")=="ok";
            }} catch { return false; }
        }
        public async Task Start(Action<string> status) {
            Address="http://127.0.0.1:"+Port();
            if(await Ready()) return;
            status("Préparation du serveur · Python et composants nécessaires…");
            var powershell=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe");
            var info=Native.StartInfo(powershell,new[]{"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",Path.Combine(Native.Root,"tools","Start-PC.ps1"),"-PrepareOnly"});
            info.RedirectStandardOutput=true; info.RedirectStandardError=true;
            bootstrap=Process.Start(info);
            var outputTask=bootstrap.StandardOutput.ReadToEndAsync(); var errorsTask=bootstrap.StandardError.ReadToEndAsync();
            bool finished=await Task.Run(()=>bootstrap.WaitForExit(1200000));
            if(!finished) { StopProcess(bootstrap); throw new IOException("La préparation a pris trop de temps. Relancez Pluxy pour la reprendre."); }
            var output=await outputTask; var errors=await errorsTask;
            if(bootstrap.ExitCode!=0) { Native.Log(new IOException(output+"\n"+errors)); throw new IOException("Impossible de préparer le serveur. Vérifiez votre connexion Internet, puis réessayez."); }
            var lines=output.Trim().Split('\n'); var prepared=Native.Object(Native.Json.DeserializeObject(lines[lines.Length-1].Trim()));
            var python=Native.Text(prepared,"Python"); if(!File.Exists(python)) throw new IOException("Python n'a pas pu être préparé.");
            status("Démarrage de votre bibliothèque…");
            var serverInfo=Native.StartInfo(python,new[]{"-E",Path.Combine(Native.Root,"server","run.py")});
            serverInfo.EnvironmentVariables["PATH"]=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","Pluxy","FFmpeg","bin")+";"+serverInfo.EnvironmentVariables["PATH"];
            server=Process.Start(serverInfo);
            for(int attempt=0;attempt<80;attempt++) {
                if(server.HasExited) throw new IOException("Le serveur s'est arrêté au démarrage. Vérifiez le port dans vos réglages.");
                if(await Ready()) return; await Task.Delay(250);
            }
            Stop(); throw new IOException("Le serveur n'a pas répondu. Réessayez ou choisissez un autre port.");
        }
        internal static void StopProcess(Process process) {
            if(process==null) return;
            try { if(process.HasExited) return;
                var info=Native.StartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"taskkill.exe"),new[]{"/PID",process.Id.ToString(),"/T","/F"});
                using(var stop=Process.Start(info)) { if(!stop.WaitForExit(6000)) stop.Kill(); }
                if(!process.HasExited) process.Kill();
            } catch(InvalidOperationException) { } catch(Exception error) { Native.Log(error); }
        }
        public void Stop() { StopProcess(server); if(server!=null) server.Dispose(); server=null; }
        public void Dispose() { StopProcess(bootstrap); if(bootstrap!=null) bootstrap.Dispose(); Stop(); }
    }
}
