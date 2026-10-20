using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal static class UpdateTests {
        private sealed class Response : HttpMessageHandler {
            private readonly Func<HttpResponseMessage> make;
            public Response(Func<HttpResponseMessage> make) { this.make=make; }
            protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancel) { cancel.ThrowIfCancellationRequested(); return Task.FromResult(make()); }
        }
        internal static async Task Run(Action<bool,string> check) {
            var now=DateTime.UtcNow;
            check(UpdateChecker.MayOffer(false,now,now.AddDays(1),now.AddDays(1).Ticks),"Une mise à jour est proposée au premier lancement malgré une proposition fermée la veille");
            check(!UpdateChecker.MayOffer(true,now,now.AddDays(1),now.AddDays(1).Ticks),"La même mise à jour ne se répète pas pendant une session déjà avertie");
            await VerifyIntegratedSession(check);
            var bytes=File.ReadAllBytes(typeof(UpdateTests).Assembly.Location);
            string hash; using(var sha=SHA256.Create()) hash=BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-","").ToLowerInvariant();
            string json=Native.Json.Serialize(new {schema=1,version="99.0.0",dirty=false,sha256=hash,size=bytes.Length});
            var release=UpdateChecker.Parse(json,"1.8.19.0");
            check(release.available && UpdateChecker.Normalize("1.8.19")==UpdateChecker.Normalize("1.8.19.0"),"Les numéros Windows à trois et quatre composantes ne déclenchent pas de fausse mise à jour");
            bool invalid=false; try { UpdateChecker.Parse(json.Replace(hash,"invalid"),"1.8.19"); } catch(IOException) { invalid=true; }
            check(invalid,"Une publication sans empreinte valide est refusée");
            using(var handler=new Response(()=>new HttpResponseMessage(HttpStatusCode.OK) {Content=new StringContent(json)})) {
                check((await UpdateChecker.Check(handler)).available,"Une nouvelle version est détectée dans le manifeste publié");
            }
            invalid=false;
            using(var handler=new Response(()=> { var response=new HttpResponseMessage(HttpStatusCode.Redirect); response.Headers.Location=new Uri("http://127.0.0.1/installer.exe"); return response; })) {
                try { await UpdateChecker.Check(handler); } catch(IOException) { invalid=true; }
            }
            check(invalid,"Les redirections hors GitHub ou sans HTTPS sont refusées");
            release.version=UpdateChecker.Current;
            string path=null;
            try {
                using(var handler=new Response(()=>new HttpResponseMessage(HttpStatusCode.OK) {Content=new ByteArrayContent(bytes)})) path=await UpdateChecker.Download(release,p=>{},CancellationToken.None,handler);
                check(File.Exists(path),"L’installateur est téléchargé et son empreinte contrôlée avant ouverture");
            } finally { if(path!=null && File.Exists(path)) File.Delete(path); }
            bytes[10]=1; invalid=false;
            int before=Directory.GetFiles(Path.Combine(Native.Data,"Updates")).Length;
            using(var handler=new Response(()=>new HttpResponseMessage(HttpStatusCode.OK) {Content=new ByteArrayContent(bytes)})) {
                try { await UpdateChecker.Download(release,p=>{},CancellationToken.None,handler); } catch(IOException) { invalid=true; }
            }
            check(invalid && Directory.GetFiles(Path.Combine(Native.Data,"Updates")).Length==before,"Un téléchargement altéré est refusé et supprimé");
            using(var cancelled=new CancellationTokenSource()) using(var handler=new Response(()=>new HttpResponseMessage(HttpStatusCode.OK) {Content=new ByteArrayContent(bytes)})) {
                cancelled.Cancel(); invalid=false;
                try { await UpdateChecker.Download(release,p=>{},cancelled.Token,handler); } catch(OperationCanceledException) { invalid=true; }
                check(invalid && Directory.GetFiles(Path.Combine(Native.Data,"Updates")).Length==before,"Une annulation ne laisse aucun installateur partiel");
            }
        }
        private static async Task VerifyIntegratedSession(Action<bool,string> check) {
            object view=null; int checks=0,installs=0;
            var pending=new TaskCompletionSource<UpdateRelease>();
            var release=new UpdateRelease {version="99.0.0",available=true};
            using(var session=new UpdateSession(path=> { installs++; return Task.FromResult(true); },state=>view=state,
                ()=> { checks++; return pending.Task; },async(value,progress,token)=> { progress(25); await Task.Delay(Timeout.Infinite,token); return "unused.exe"; })) {
                var opening=session.Open();
                await session.Handle("check");
                check(checks==1 && session.Visible,"Le panneau intégré évite les vérifications simultanées");
                pending.SetResult(release); await opening;
                var download=session.Handle("install");
                var state=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(view)));
                check(Native.Text(state,"phase")=="downloading" && Native.Number(state,"percent")==25,"La progression de mise à jour est transmise à l'interface interne");
                await session.Handle("dismiss"); await download;
                check(!session.Visible && installs==0,"Fermer le panneau annule le téléchargement sans lancer d'installation");
                await session.Open();
                state=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(view)));
                check(session.Visible && Native.Text(state,"phase")=="available","Le panneau peut être rouvert et réessayé après une annulation");
            }
            using(var session=new UpdateSession(path=> { installs++; return Task.FromResult(true); },state=>view=state,
                ()=>Task.FromResult(release),(value,progress,token)=>Task.FromException<string>(new IOException("Téléchargement refusé")))) {
                await session.Open(release); await session.Handle("install");
                var state=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(view)));
                check(installs==0 && Native.Text(state,"phase")=="error" && !(bool)state["available"],"Une erreur reste dans le panneau intégré et impose une nouvelle vérification");
            }
        }
    }
}
