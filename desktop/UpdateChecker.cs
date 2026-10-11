using System;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class UpdateRelease {
        public string version,sha256;
        public long size;
        public bool available;
    }
    internal static class UpdateChecker {
        internal const string Base="https://github.com/Amau54/Pluxy/releases/download/pc-latest/";
        public static string Current { get { return Normalize(System.Diagnostics.FileVersionInfo.GetVersionInfo(typeof(UpdateChecker).Assembly.Location).ProductVersion).ToString(); } }
        internal static Version Normalize(string value) {
            Version version;
            if(!Version.TryParse(value,out version) || version.Major<0 || version.Minor<0 || version.Build<0) throw new IOException("Numéro de version invalide.");
            return new Version(version.Major,version.Minor,version.Build,Math.Max(0,version.Revision));
        }
        internal static UpdateRelease Parse(string json,string installed) {
            var info=Native.Object(Native.Json.DeserializeObject(json));
            var version=Native.Text(info,"version"); var hash=Native.Text(info,"sha256").ToLowerInvariant();
            long size;
            if(Native.Number(info,"schema")!=1 || Native.Text(info,"dirty").ToLowerInvariant()!="false" || !Regex.IsMatch(hash,"^[a-f0-9]{64}$") || !long.TryParse(Native.Text(info,"size"),out size) || size<1024 || size>256L*1024*1024)
                throw new IOException("Les informations de mise à jour sont invalides.");
            return new UpdateRelease {version=version,sha256=hash,size=size,available=Normalize(version)>Normalize(installed)};
        }
        internal static bool Trusted(Uri uri) { return uri.Scheme=="https" && uri.IsDefaultPort && (uri.Host=="github.com" || uri.Host=="release-assets.githubusercontent.com" || uri.Host=="objects.githubusercontent.com"); }
        private static HttpClient Client(HttpMessageHandler handler) {
            var http=new HttpClient(handler ?? new HttpClientHandler { AllowAutoRedirect=false }) { Timeout=Timeout.InfiniteTimeSpan };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("Pluxy-Windows/"+Current);
            http.DefaultRequestHeaders.CacheControl=new System.Net.Http.Headers.CacheControlHeaderValue { NoCache=true };
            return http;
        }
        private static async Task<HttpResponseMessage> Get(HttpClient http,string url,CancellationToken cancel) {
            var uri=new Uri(url);
            for(int redirects=0;redirects<6;redirects++) {
                if(!Trusted(uri)) throw new IOException("Source de téléchargement invalide.");
                var response=await http.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,cancel);
                if((int)response.StatusCode>=300 && (int)response.StatusCode<400) {
                    var location=response.Headers.Location; response.Dispose();
                    if(location==null) throw new IOException("Redirection de téléchargement invalide.");
                    uri=location.IsAbsoluteUri?location:new Uri(uri,location); continue;
                }
                if(!response.IsSuccessStatusCode) { response.Dispose(); throw new IOException("GitHub est indisponible. Réessayez plus tard."); }
                return response;
            }
            throw new IOException("Trop de redirections de téléchargement.");
        }
        public static async Task<UpdateRelease> Check(HttpMessageHandler handler=null) {
            using(var cancel=new CancellationTokenSource(TimeSpan.FromSeconds(20)))
            using(var http=Client(handler))
            using(var response=await Get(http,Base+"PC_BUILD_INFO.json",cancel.Token))
            using(var input=await response.Content.ReadAsStreamAsync())
            using(var output=new MemoryStream()) {
                var bytes=new byte[8192]; int read;
                while((read=await input.ReadAsync(bytes,0,bytes.Length,cancel.Token))>0) {
                    if(output.Length+read>256*1024) throw new IOException("Réponse de mise à jour invalide.");
                    output.Write(bytes,0,read);
                }
                return Parse(Encoding.UTF8.GetString(output.ToArray()),Current);
            }
        }
        internal static void Verify(string path,UpdateRelease release) {
            using(var input=File.OpenRead(path)) using(var hash=SHA256.Create()) {
                if(input.Length!=release.size || BitConverter.ToString(hash.ComputeHash(input)).Replace("-","").ToLowerInvariant()!=release.sha256)
                    throw new IOException("Le téléchargement est incomplet ou a changé. Relancez la vérification.");
                input.Position=0;
                if(input.ReadByte()!=0x4d || input.ReadByte()!=0x5a) throw new IOException("Le fichier reçu n’est pas un installateur Windows.");
            }
            if(Normalize(System.Diagnostics.FileVersionInfo.GetVersionInfo(path).ProductVersion)!=Normalize(release.version)) throw new IOException("La version de l’installateur ne correspond pas à la publication.");
        }
        public static async Task<string> Download(UpdateRelease release,Action<int> progress,CancellationToken cancel,HttpMessageHandler handler=null) {
            if(!release.available) throw new IOException("Pluxy est déjà à jour.");
            var directory=Path.Combine(Native.Data,"Updates"); Directory.CreateDirectory(directory);
            var path=Path.Combine(directory,"Pluxy-Setup-"+Guid.NewGuid().ToString("N")+".exe");
            foreach(var old in Directory.GetFiles(directory,"Pluxy-Setup-*.exe")) { try { if(File.GetLastWriteTimeUtc(old)<DateTime.UtcNow.AddDays(-2)) File.Delete(old); } catch(IOException) { } }
            try {
                using(var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancel)) {
                    deadline.CancelAfter(TimeSpan.FromMinutes(10));
                    using(var http=Client(handler))
                    using(var response=await Get(http,Base+"Pluxy-Setup.exe",deadline.Token))
                    using(var input=await response.Content.ReadAsStreamAsync())
                    using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None,65536,true)) {
                        if(response.Content.Headers.ContentLength.HasValue && response.Content.Headers.ContentLength.Value!=release.size) throw new IOException("La version publiée a changé. Relancez la vérification.");
                        var bytes=new byte[65536]; long total=0; int read,last=-1;
                        while((read=await input.ReadAsync(bytes,0,bytes.Length,deadline.Token))>0) {
                            total+=read; if(total>release.size) throw new IOException("Taille de téléchargement invalide.");
                            await output.WriteAsync(bytes,0,read,deadline.Token);
                            int percent=(int)(total*100/release.size); if(percent!=last) { last=percent; progress(percent); }
                        }
                    }
                }
                await Task.Run(()=>Verify(path,release),cancel); return path;
            } catch { if(File.Exists(path)) File.Delete(path); throw; }
        }
    }
}
