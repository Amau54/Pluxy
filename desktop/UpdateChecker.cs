using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using System.Threading;

namespace Pluxy {
    internal static class UpdateChecker {
        private const string Release="https://github.com/Amau54/Pluxy/releases/tag/pc-latest";
        private static async Task<object> Read(HttpClient http,string url) {
            using(var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(8)))
            using(var response=await http.GetAsync(url,HttpCompletionOption.ResponseHeadersRead,deadline.Token)) {
                response.EnsureSuccessStatusCode();
                if(response.Content.Headers.ContentLength>256*1024) throw new IOException("Réponse de mise à jour invalide.");
                using(var input=await response.Content.ReadAsStreamAsync()) using(var output=new MemoryStream()) {
                    var bytes=new byte[8192]; int read;
                    while((read=await input.ReadAsync(bytes,0,bytes.Length,deadline.Token))>0) { if(output.Length+read>256*1024) throw new IOException("Réponse de mise à jour invalide."); output.Write(bytes,0,read); }
                    return Native.Json.DeserializeObject(System.Text.Encoding.UTF8.GetString(output.ToArray()));
                }
            }
        }
        public static async Task<object> Check() {
            using(var http=new HttpClient { Timeout=TimeSpan.FromSeconds(8) }) {
                http.DefaultRequestHeaders.UserAgent.ParseAdd("Pluxy-Windows/1.0");
                var release=Native.Object(await Read(http,"https://api.github.com/repos/Amau54/Pluxy/releases/tags/pc-latest"));
                var assets=release.ContainsKey("assets")?release["assets"] as object[]:null;
                var asset=(assets ?? new object[0]).Select(Native.Object).FirstOrDefault(value=>Native.Text(value,"name")=="PC_BUILD_INFO.json");
                if(asset==null) throw new IOException("Aucune version Windows vérifiable n'est disponible.");
                string url=Native.Text(asset,"browser_download_url");
                if(!url.StartsWith("https://github.com/Amau54/Pluxy/releases/download/",StringComparison.Ordinal)) throw new IOException("Source de mise à jour invalide.");
                var info=Native.Object(await Read(http,url)); Version available,current;
                var installed=System.Diagnostics.FileVersionInfo.GetVersionInfo(System.Reflection.Assembly.GetExecutingAssembly().Location).ProductVersion;
                if(!Version.TryParse(Native.Text(info,"version"),out available) || !Version.TryParse(installed,out current)) throw new IOException("Numéro de version invalide.");
                return new { version=available.ToString(),current=current.ToString(),available=available>current,url=Release };
            }
        }
    }
}
