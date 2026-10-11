using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class ServerController : IDisposable {
        private Process bootstrap;
        private Process server;
        private bool attached;
        private int generation;
        private readonly object processGate=new object();
        private bool disposed;
        public string Address { get; private set; }
        public bool Owned { get { var managed=server; try { return managed != null && !managed.HasExited; } catch(InvalidOperationException) { return false; } } }
        public bool Running { get { return Owned || attached; } }
        public async Task Refresh() {
            int observed=System.Threading.Volatile.Read(ref generation);
            bool found=!Owned && Address!=null && await Ready();
            lock(processGate) { if(!disposed && observed==generation) attached=found; }
        }
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
            System.Threading.Interlocked.Increment(ref generation);
            Address="http://127.0.0.1:"+Port();
            if(await Ready()) { attached=!Owned; return; }
            attached=false;
            status("Préparation du serveur · Python et composants nécessaires…");
            var powershell=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe");
            var info=Native.StartInfo(powershell,new[]{"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",Path.Combine(Native.Root,"tools","Start-PC.ps1"),"-PrepareOnly"});
            info.RedirectStandardOutput=true; info.RedirectStandardError=true;
            lock(processGate) {
                if(disposed) throw new ObjectDisposedException("ServerController");
                bootstrap=Process.Start(info);
            }
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
            lock(processGate) {
                if(disposed) throw new ObjectDisposedException("ServerController");
                server=Process.Start(serverInfo);
            }
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
        public void Stop() {
            Process managed;
            lock(processGate) { generation++; attached=false; managed=server; server=null; }
            StopProcess(managed); if(managed!=null) managed.Dispose();
        }
        public async Task Restart(Action<string> status) {
            if(Running && !Owned) throw new IOException("Ce serveur a été démarré par une autre application.");
            await Task.Run(()=>Stop());
            await Start(status);
        }
        public void Dispose() {
            Process preparation;
            lock(processGate) { disposed=true; preparation=bootstrap; bootstrap=null; }
            StopProcess(preparation); if(preparation!=null) preparation.Dispose(); Stop();
        }
    }
}
