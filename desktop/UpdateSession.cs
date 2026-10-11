using System;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class UpdateSession : IDisposable {
        private readonly Func<string,Task> install;
        private readonly Action<object> publish;
        private readonly Func<Task<UpdateRelease>> check;
        private readonly Func<UpdateRelease,Action<int>,CancellationToken,Task<string>> download;
        private CancellationTokenSource cancel;
        private UpdateRelease release;
        private string phase="idle",status="";
        private int percent;
        private bool busy,disposed;
        public bool Visible { get; private set; }
        public UpdateSession(Func<string,Task> install,Action<object> publish,Func<Task<UpdateRelease>> check=null,Func<UpdateRelease,Action<int>,CancellationToken,Task<string>> download=null) {
            this.install=install; this.publish=publish;
            this.check=check ?? (()=>UpdateChecker.Check());
            this.download=download ?? ((value,progress,token)=>UpdateChecker.Download(value,progress,token));
        }
        public void Publish() {
            if(!disposed) publish(new {visible=Visible,phase=phase,status=status,percent=percent,busy=busy,available=release!=null && release.available,version=release==null?null:release.version,current=UpdateChecker.Normalize(UpdateChecker.Current).ToString(3)});
        }
        public async Task Open(UpdateRelease known=null) {
            Visible=true;
            if(busy) { Publish(); return; }
            if(known!=null) release=known;
            if(release==null) await Check(); else Present();
        }
        private void Present() {
            phase=release.available?"available":"current";
            status=release.available?"Pluxy "+release.version+" est disponible. Vos réglages et votre bibliothèque seront conservés.":"Vous utilisez la dernière version de Pluxy.";
            Publish();
        }
        public async Task Handle(string action) {
            if(action=="dismiss") { Visible=false; if(cancel!=null) cancel.Cancel(); Publish(); return; }
            if(action=="state") { Publish(); return; }
            if(busy || disposed) return;
            if(action=="check") await Check();
            else if(action=="install") { if(release!=null && release.available) await Download(); else await Check(); }
        }
        private async Task Check() {
            busy=true; phase="checking"; status="Recherche de la dernière version…"; Publish();
            try { release=await check(); Present(); }
            catch(Exception error) { phase="error"; status=error.Message; }
            finally { busy=false; Publish(); }
        }
        private async Task Download() {
            busy=true; phase="downloading"; percent=0; status="Téléchargement de Pluxy "+release.version+"…";
            cancel=new CancellationTokenSource(); Publish();
            try {
                var path=await download(release,value=> { percent=value; status="Téléchargement · "+value+" %"; Publish(); },cancel.Token);
                cancel.Token.ThrowIfCancellationRequested();
                phase="installing"; status="Installation… Confirmez la demande de Windows."; Publish();
                await install(path);
            } catch(OperationCanceledException) { phase="available"; status="Téléchargement annulé. Vous pourrez réessayer."; }
            catch(Exception error) { phase="error"; status=error.Message; release=null; }
            finally { busy=false; cancel.Dispose(); cancel=null; Publish(); }
        }
        public void Dispose() { disposed=true; if(cancel!=null) cancel.Cancel(); }
    }
}
