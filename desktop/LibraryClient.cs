using System;
using System.Collections.Generic;
using System.IO;
using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class LibraryClient : IDisposable {
        private readonly HttpClient http;
        private readonly ProgressOutbox progress;
        public string Server { get; private set; }
        public string ProfileId="default";
        public string Session = Guid.NewGuid().ToString("N");
        public LibraryClient() {
            http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(15) };
            http.DefaultRequestHeaders.Add("X-Pluxy-Session", Session);
            progress=new ProgressOutbox(Path.Combine(Native.Data,"progress-outbox.json"),SendProgress);
        }
        public static string NormalizeServer(string value) {
            value = (value ?? "").Trim(); if(!value.Contains("://")) value = "http://" + value;
            Uri uri;
            if(!Uri.TryCreate(value,UriKind.Absolute,out uri) || (uri.Scheme!="http" && uri.Scheme!="https") || !string.IsNullOrEmpty(uri.UserInfo) || uri.AbsolutePath!="/" || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment)) throw new ArgumentException("Saisissez l'adresse du serveur, par exemple 192.168.1.20:8420.");
            var authority=value.Substring(value.IndexOf("://",StringComparison.Ordinal)+3).TrimEnd('/');
            if(Regex.IsMatch(authority,"^[0-9]+$")) throw new ArgumentException("Saisissez l'adresse IP du serveur et non son port seul, par exemple 192.168.1.20:8420.");
            bool explicitPort=authority.StartsWith("[",StringComparison.Ordinal)?authority.Contains("]:"):authority.Contains(":");
            if(uri.Scheme=="http" && !explicitPort) uri=new UriBuilder(uri){Port=8420}.Uri;
            return uri.GetLeftPart(UriPartial.Authority).TrimEnd('/');
        }
        public static string ItemPath(string id) {
            if(!Regex.IsMatch(id ?? "", "^[A-Za-z0-9_-]{1,128}$")) throw new ArgumentException("Média invalide.");
            return Uri.EscapeDataString(id);
        }
        public async Task<byte[]> SeekPreview(string server,string id,long second,CancellationToken cancellation) {
            using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation)) {
                timeout.CancelAfter(2000);
                using(var response=await http.GetAsync(server+"/api/library/items/"+ItemPath(id)+"/preview?position="+second.ToString(CultureInfo.InvariantCulture),HttpCompletionOption.ResponseHeadersRead,timeout.Token)) {
                    if((int)response.StatusCode==202) return null;
                    if((int)response.StatusCode==404) return new byte[0];
                    response.EnsureSuccessStatusCode();
                    if(response.Content.Headers.ContentLength>256*1024) throw new IOException("Aperçu trop volumineux.");
                    using(var input=await response.Content.ReadAsStreamAsync()) using(var output=new MemoryStream()) {
                        var buffer=new byte[8192]; int count;
                        while((count=await input.ReadAsync(buffer,0,buffer.Length,timeout.Token))>0) {
                            if(output.Length+count>256*1024) throw new IOException("Aperçu trop volumineux.");
                            output.Write(buffer,0,count);
                        }
                        return output.ToArray();
                    }
                }
            }
        }
        private async Task<object> Get(string server, string path) {
            using(var request=new HttpRequestMessage(HttpMethod.Get,server+path)) {
            request.Headers.Add("X-Pluxy-Profile",ProfileId);
            using(var response = await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead)) {
                if(!response.IsSuccessStatusCode) throw new IOException("Le serveur ne répond pas correctement (" + (int)response.StatusCode + ").");
                const int limit=16*1024*1024;
                if(response.Content.Headers.ContentLength>limit) throw new IOException("Réponse trop volumineuse.");
                using(var input=await response.Content.ReadAsStreamAsync())
                using(var output=new MemoryStream())
                using(var timeout=new CancellationTokenSource(15000)) {
                    var buffer=new byte[65536]; int count;
                    while((count=await input.ReadAsync(buffer,0,buffer.Length,timeout.Token))>0) {
                        if(output.Length+count>limit) throw new IOException("Réponse trop volumineuse.");
                        output.Write(buffer,0,count);
                    }
                    return Native.Json.DeserializeObject(Encoding.UTF8.GetString(output.ToArray()).TrimStart('\uFEFF'));
                }
            }
        }}
        public async Task<object> Connect(string address) {
            var candidate = NormalizeServer(address); var health = Native.Object(await Get(candidate,"/health"));
            if(Native.Text(health,"app")!="pluxy" || Native.Text(health,"status")!="ok") throw new IOException("Aucun serveur Pluxy à cette adresse.");
            var runtime = Native.Object(await Get(candidate,"/api/settings/client")); Server = candidate;
            var settings=Native.Object(runtime.ContainsKey("server")?runtime["server"]:null);
            return new { url = candidate, name = Native.Text(runtime,"server_name",Native.Text(settings,"name","Pluxy")) };
        }
        public async Task<object[]> ConversionProfiles(string server) {
            var runtime=Native.Object(await Get(server,"/api/settings/client"));
            object profiles;
            return runtime.TryGetValue("conversion_profiles",out profiles)?profiles as object[] ?? new object[0]:new object[0];
        }
        internal static UdpClient DiscoverySocket() {
            var udp=new UdpClient(0);
            try {
                // Unrelated closed UDP ports must not abort discovery on Windows.
                udp.Client.IOControl(unchecked((int)0x9800000C),new byte[4],null);
                udp.EnableBroadcast=true;
                return udp;
            } catch { udp.Dispose(); throw; }
        }
        public async Task<object> Discover() {
            var discovered = new Dictionary<string,object>();
            var addresses = new List<IPAddress> { IPAddress.Broadcast, IPAddress.Loopback };
            addresses.AddRange(LanDiscoveryTargets.UnicastTargets());
            foreach(var adapter in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()) {
                if(adapter.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                foreach(var address in adapter.GetIPProperties().UnicastAddresses) {
                    if(address.Address.AddressFamily!=AddressFamily.InterNetwork || address.IPv4Mask==null) continue;
                    var bytes=address.Address.GetAddressBytes(); var mask=address.IPv4Mask.GetAddressBytes();
                    for(int i=0;i<4;i++) bytes[i]=(byte)(bytes[i]|~mask[i]); addresses.Add(new IPAddress(bytes));
                }
            }
            using(var udp = DiscoverySocket()) {
                var magic=Encoding.UTF8.GetBytes("PLUXY_DISCOVERY_V1");
                foreach(var address in addresses) { try { await udp.SendAsync(magic,magic.Length,new IPEndPoint(address,8421)); } catch(SocketException) { } }
                var deadline=DateTime.UtcNow.AddSeconds(2);
                while(DateTime.UtcNow<deadline) {
                    try {
                        var remaining=deadline-DateTime.UtcNow; if(remaining<=TimeSpan.Zero) break;
                        var receive=udp.ReceiveAsync();
                        if(await Task.WhenAny(receive,Task.Delay(remaining))!=receive) break;
                        var reply=await receive; if(reply.Buffer.Length>4096) continue;
                        var data=Native.Object(Native.Json.DeserializeObject(Encoding.UTF8.GetString(reply.Buffer)));
                        if(Native.Text(data,"app")!="pluxy") continue;
                        int port=(int)Native.Number(data,"port",8420); if(port<1 || port>65535) continue;
                        var host=reply.RemoteEndPoint.Address;
                        IPAddress advertised;
                        if(IPAddress.IsLoopback(host) && IPAddress.TryParse(Native.Text(data,"ip"),out advertised) && advertised.AddressFamily==AddressFamily.InterNetwork) host=advertised;
                        var url="http://"+host+":"+port;
                        discovered[url]=new { url=url, name=Native.Text(data,"name","Pluxy") };
                        if(discovered.Count>=16) break;
                    } catch(Exception error) { if(!(error is SocketException || error is ArgumentException)) Native.Log(error); }
                }
            }
            var valid = new List<object>();
            using(var probe=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(3)}) {
                var checks=new List<Task>();
                foreach(var entry in discovered) { var candidate=entry; checks.Add(Task.Run(async()=> {
                    try { var health=Native.Object(Native.Json.DeserializeObject(await probe.GetStringAsync(candidate.Key+"/health"))); if(Native.Text(health,"app")=="pluxy" && Native.Text(health,"status")=="ok") lock(valid) valid.Add(candidate.Value); } catch { }
                })); }
                await Task.WhenAll(checks);
            }
            return valid;
        }
        public async Task<object> Api(string method, IDictionary<string,object> args) {
            var server=Server; if(string.IsNullOrEmpty(server)) throw new IOException("Choisissez un serveur.");
            string id=Native.Text(args,"id");
            if(method=="ping") return await Get(server,"/health");
            if(method=="profiles") return await Get(server,"/api/profiles");
            if(method=="createProfile") {
                using(var response=await http.PostAsync(server+"/api/profiles",new StringContent(Native.Json.Serialize(new {name=Native.Text(args,"name")}),Encoding.UTF8,"application/json"))) {
                    if(!response.IsSuccessStatusCode) throw new IOException("Impossible de créer ce profil. Vérifiez son nom et le nombre de profils.");
                    return Native.Json.DeserializeObject(await response.Content.ReadAsStringAsync());
                }
            }
            if(method=="home") return await Get(server,"/api/library/home");
            if(method=="facets") return await Get(server,"/api/library/facets?kind="+Uri.EscapeDataString(Native.Text(args,"kind","movie")));
            if(method=="matches") return await Get(server,"/api/library/items/"+ItemPath(id)+"/matches?q="+Uri.EscapeDataString(Native.Text(args,"q")));
            if(method=="chooseMatch") {
                using(var response=await http.PostAsync(server+"/api/library/items/"+ItemPath(id)+"/match",new StringContent(Native.Json.Serialize(new{tmdb_id=(int)Native.Number(args,"tmdb_id")}),Encoding.UTF8,"application/json"))) {
                    if(!response.IsSuccessStatusCode) throw new IOException("Cette correspondance est indisponible. Vérifiez TMDB et réessayez.");
                    return Native.Json.DeserializeObject(await response.Content.ReadAsStringAsync());
                }
            }
            if(method=="metadata") return await Get(server,"/api/library/items/"+ItemPath(id)+"/metadata");
            if(method=="item") return await Get(server,"/api/library/items/"+ItemPath(id));
            if(method=="progress") {
                var local=progress.Get(server,ProfileId,id);
                if(local!=null) return new { position_ms=local.positionMs>local.durationMs*.95?0:local.positionMs,duration_ms=local.durationMs,watched=local.positionMs>local.durationMs*.95 };
                return await Get(server,"/api/playback/progress/"+ItemPath(id));
            }
            if(method=="page") {
                string[] fields={"kind","q","sort","descending","year","genre","watched","resolution","hdr_only","offset","revision","grouped","group_id","audio_folders"};
                var query=new List<string>{"limit=500"};
                foreach(var field in fields) { var text=Native.Text(args,field); if(text.Length>512) throw new ArgumentException("Filtre trop long."); if(text!="") query.Add(field+"="+Uri.EscapeDataString(text)); }
                return await Get(server,"/api/library/items/page?"+string.Join("&",query.ToArray()));
            }
            throw new ArgumentException("Commande inconnue.");
        }
        public Task SaveProgress(string server,string id,double position,double duration,string sessionId=null,long? sequence=null,string profileId=null) {
            if(duration>0 && !double.IsNaN(position) && !double.IsInfinity(position) && !double.IsInfinity(duration)) {
                server=NormalizeServer(server); ItemPath(id);
                progress.Enqueue(new ProgressUpdate { server=server,id=id,profile=profileId ?? ProfileId,session=sessionId ?? Session,sequence=sequence ?? DateTime.UtcNow.Ticks,positionMs=(long)(Math.Max(0,Math.Min(position,duration))*1000),durationMs=(long)(duration*1000) });
            }
            return Task.FromResult(true);
        }
        private async Task SendProgress(ProgressUpdate update) {
            var body=Native.Json.Serialize(new { position_ms=update.positionMs,duration_ms=update.durationMs,session_id=update.session,sequence=update.sequence });
            using(var request=new HttpRequestMessage(HttpMethod.Post,update.server+"/api/playback/progress/"+ItemPath(update.id))) {
                request.Headers.Add("X-Pluxy-Profile",update.profile);
                request.Content=new StringContent(body,Encoding.UTF8,"application/json");
                using(var response=await http.SendAsync(request)) response.EnsureSuccessStatusCode();
            }
        }
        public async Task<object[]> Chapters(string server,string id) { return await Get(server,"/api/library/items/"+ItemPath(id)+"/chapters") as object[] ?? new object[0]; }
        public async Task<object[]> Group(string id,string kind) {
            var values=new List<object>(); int offset=0; string revision="";
            for(int retry=0;retry<2;retry++) {
                values.Clear(); offset=0; revision="";
                try {
                    while(true) {
                        var page=Native.Object(await Api("page",new Dictionary<string,object>{{"kind",kind},{"audio_folders",kind=="audio"},{"group_id",ItemPath(id)},{"offset",offset},{"revision",revision}}));
                        values.AddRange(page["items"] as object[] ?? new object[0]); revision=Native.Text(page,"revision");
                        object next; if(!page.TryGetValue("next_offset",out next) || next==null) return values.ToArray();
                        int following=Convert.ToInt32(next); if(following<=offset) throw new IOException("Pagination incorrecte du serveur."); offset=following;
                    }
                } catch(IOException error) { if(retry!=0 || !error.Message.Contains("409")) throw; }
            }
            throw new IOException("Le catalogue change trop rapidement. Réessayez.");
        }
        public async Task<string> PlaybackServer(string previous,string id) {
            var tried=new HashSet<string>();
            foreach(var candidate in new[]{previous,Server}) {
                if(string.IsNullOrEmpty(candidate) || !tried.Add(candidate)) continue;
                try { await Get(candidate,"/api/library/items/"+ItemPath(id)); return candidate; } catch { }
            }
            var found=Native.Json.DeserializeObject(Native.Json.Serialize(await Discover())) as object[] ?? new object[0];
            if(found.Length!=1) throw new IOException(found.Length>1?"Plusieurs serveurs disponibles. Choisissez le vôtre dans la bibliothèque, puis réessayez.":"Le serveur est indisponible. Réessayez lorsqu'il est démarré.");
            string address=Native.Text(Native.Object(found[0]),"url"); await Get(address,"/api/library/items/"+ItemPath(id)); return address;
        }
        public async Task<MemoryStream> Image(string server,string path) {
            if(server!=Server || !Regex.IsMatch(path,"^/(api/library/items/[A-Za-z0-9_-]{1,128}/thumbnail|stream/direct/[A-Za-z0-9_-]{1,128})$")) throw new ArgumentException("Image indisponible.");
            using(var response=await http.GetAsync(server+path,HttpCompletionOption.ResponseHeadersRead)) {
                response.EnsureSuccessStatusCode();
                if(response.Content.Headers.ContentLength>32*1024*1024) throw new IOException("Image trop volumineuse.");
                var output=new MemoryStream();
                try { using(var input=await response.Content.ReadAsStreamAsync()) {
                    var buffer=new byte[65536]; int count;
                    using(var timeout=new CancellationTokenSource(15000)) while((count=await input.ReadAsync(buffer,0,buffer.Length,timeout.Token))>0) { if(output.Length+count>32*1024*1024) throw new IOException("Image trop volumineuse."); output.Write(buffer,0,count); }
                } output.Position=0; return output; } catch { output.Dispose(); throw; }
            }
        }
        public void Dispose() { progress.Dispose(); http.Dispose(); }
        public Task SuspendProgress() { return progress.Suspend(); }
        public void ResumeProgress() { progress.Resume(); }
    }
}
