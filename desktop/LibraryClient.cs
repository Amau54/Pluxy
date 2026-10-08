using System;
using System.Collections.Generic;
using System.IO;
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
        public string Server { get; private set; }
        public string Session = Guid.NewGuid().ToString("N");
        public LibraryClient() {
            http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(15) };
            http.DefaultRequestHeaders.Add("X-Pluxy-Session", Session);
        }
        public static string NormalizeServer(string value) {
            value = value.Trim(); if(!value.Contains("://")) value = "http://" + value;
            Uri uri;
            if(!Uri.TryCreate(value,UriKind.Absolute,out uri) || (uri.Scheme!="http" && uri.Scheme!="https") || !string.IsNullOrEmpty(uri.UserInfo) || uri.AbsolutePath!="/" || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment)) throw new ArgumentException("Saisissez l'adresse du serveur, par exemple 192.168.1.20:8420.");
            return uri.GetLeftPart(UriPartial.Authority).TrimEnd('/');
        }
        public static string ItemPath(string id) {
            if(!Regex.IsMatch(id ?? "", "^[A-Za-z0-9_-]{1,128}$")) throw new ArgumentException("Média invalide.");
            return Uri.EscapeDataString(id);
        }
        private async Task<object> Get(string server, string path) {
            using(var response = await http.GetAsync(server + path,HttpCompletionOption.ResponseHeadersRead)) {
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
        }
        public async Task<object> Connect(string address) {
            var candidate = NormalizeServer(address); var health = Native.Object(await Get(candidate,"/health"));
            if(Native.Text(health,"app")!="pluxy" || Native.Text(health,"status")!="ok") throw new IOException("Aucun serveur Pluxy à cette adresse.");
            var runtime = Native.Object(await Get(candidate,"/api/settings/client")); Server = candidate;
            var settings=Native.Object(runtime.ContainsKey("server")?runtime["server"]:null);
            return new { url = candidate, name = Native.Text(runtime,"server_name",Native.Text(settings,"name","Pluxy")) };
        }
        public async Task<object> Discover() {
            var discovered = new Dictionary<string,object>();
            var addresses = new List<IPAddress> { IPAddress.Broadcast, IPAddress.Loopback };
            foreach(var adapter in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()) {
                if(adapter.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                foreach(var address in adapter.GetIPProperties().UnicastAddresses) {
                    if(address.Address.AddressFamily!=AddressFamily.InterNetwork || address.IPv4Mask==null) continue;
                    var bytes=address.Address.GetAddressBytes(); var mask=address.IPv4Mask.GetAddressBytes();
                    for(int i=0;i<4;i++) bytes[i]=(byte)(bytes[i]|~mask[i]); addresses.Add(new IPAddress(bytes));
                }
            }
            using(var udp = new UdpClient(0)) {
                udp.EnableBroadcast = true; var magic=Encoding.UTF8.GetBytes("PLUXY_DISCOVERY_V1");
                foreach(var address in addresses) { try { await udp.SendAsync(magic,magic.Length,new IPEndPoint(address,8421)); } catch(SocketException) { } }
                var deadline=DateTime.UtcNow.AddSeconds(2);
                while(DateTime.UtcNow<deadline) {
                    var receive=udp.ReceiveAsync(); var remaining=deadline-DateTime.UtcNow;
                    if(await Task.WhenAny(receive,Task.Delay(remaining))!=receive) break;
                    try {
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
                    try { var health=Native.Object(Native.Json.DeserializeObject(await probe.GetStringAsync(candidate.Key+"/health"))); if(Native.Text(health,"app")=="pluxy") lock(valid) valid.Add(candidate.Value); } catch { }
                })); }
                await Task.WhenAll(checks);
            }
            return valid;
        }
        public async Task<object> Api(string method, IDictionary<string,object> args) {
            var server=Server; if(string.IsNullOrEmpty(server)) throw new IOException("Choisissez un serveur.");
            string id=Native.Text(args,"id");
            if(method=="ping") return await Get(server,"/health");
            if(method=="home") return await Get(server,"/api/library/home");
            if(method=="facets") return await Get(server,"/api/library/facets?kind="+Uri.EscapeDataString(Native.Text(args,"kind","movie")));
            if(method=="metadata") return await Get(server,"/api/library/items/"+ItemPath(id)+"/metadata");
            if(method=="item") return await Get(server,"/api/library/items/"+ItemPath(id));
            if(method=="progress") return await Get(server,"/api/playback/progress/"+ItemPath(id));
            if(method=="page") {
                string[] fields={"kind","q","sort","descending","year","genre","watched","resolution","hdr_only","offset","revision"};
                var query=new List<string>{"limit=500"};
                foreach(var field in fields) { var text=Native.Text(args,field); if(text.Length>512) throw new ArgumentException("Filtre trop long."); if(text!="") query.Add(field+"="+Uri.EscapeDataString(text)); }
                return await Get(server,"/api/library/items/page?"+string.Join("&",query.ToArray()));
            }
            throw new ArgumentException("Commande inconnue.");
        }
        public async Task SaveProgress(string server,string id,double position,double duration,string sessionId=null,long? sequence=null) {
            if(duration<=0) return;
            var body=Native.Json.Serialize(new { position_ms=(long)(Math.Max(0,position)*1000),duration_ms=(long)(duration*1000),session_id=sessionId,sequence=sequence });
            using(var response=await http.PostAsync(server+"/api/playback/progress/"+ItemPath(id),new StringContent(body,Encoding.UTF8,"application/json"))) response.EnsureSuccessStatusCode();
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
        public void Dispose() { http.Dispose(); }
    }
}
