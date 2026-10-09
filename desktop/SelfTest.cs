using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;

namespace Pluxy {
    internal static class SelfTest {
        private static readonly List<string> checks=new List<string>();
        private static void Check(bool passed,string message) { if(!passed) throw new Exception(message); checks.Add(message); }
        private static string Argument(string[] args,string name) { int at=Array.IndexOf(args,name); return at>=0 && at+1<args.Length?args[at+1]:null; }
        private static async Task<double> WaitPosition(MpvSession player,double expected) {
            double position=-1;
            for(int i=0;i<60;i++) {
                try { var value=await player.Command("get_property","time-pos"); if(value!=null) position=Convert.ToDouble(value); } catch(IOException) { }
                if(Math.Abs(position-expected)<1) return position;
                await Task.Delay(100);
            }
            return position;
        }
        public static int Run(string[] args) {
            string report=Argument(args,"--report") ?? Path.Combine(Native.Data,"self-test.json"); int code=0; string error=null;
            try { Verify(args).GetAwaiter().GetResult(); } catch(Exception exception) { error=exception.ToString(); code=1; }
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(report)));
            File.WriteAllText(report,Native.Json.Serialize(new{success=code==0,checks=checks,error=error})); return code;
        }
        private static async Task Verify(string[] args) {
            Check(LibraryClient.NormalizeServer("192.168.1.20:8420")=="http://192.168.1.20:8420","Adresse du serveur normalisée");
            Check(LibraryClient.NormalizeServer("192.168.1.20")=="http://192.168.1.20:8420" && LibraryClient.NormalizeServer("http://192.168.1.20")=="http://192.168.1.20:8420","Port Pluxy ajouté à une adresse IP seule");
            Check(LibraryClient.NormalizeServer("http://192.168.1.20:80")=="http://192.168.1.20" && LibraryClient.NormalizeServer("https://192.168.1.20")=="https://192.168.1.20","Ports explicitement choisis conservés");
            var lan=System.Net.IPAddress.Parse("192.168.1.70"); var mask=System.Net.IPAddress.Parse("255.255.255.0");
            Check(LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse("192.168.1.104")) && !LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse("172.18.11.218")),"Découverte directe limitée au sous-réseau local");
            var candidates=LanDiscoveryTargets.SubnetCandidates(lan,mask);
            Check(candidates.Count==253 && candidates.Contains(System.Net.IPAddress.Parse("192.168.1.104")),"Découverte d'un Android absent du cache réseau");
            Check(LanDiscoveryTargets.SubnetCandidates(lan,System.Net.IPAddress.Parse("255.255.255.224")).Count==29 && LanDiscoveryTargets.SubnetCandidates(System.Net.IPAddress.Parse("10.20.30.40"),System.Net.IPAddress.Parse("255.255.0.0")).Count<=256 && LanDiscoveryTargets.SubnetCandidates(System.Net.IPAddress.Parse("8.8.8.8"),mask).Count==0,"Recherche locale bornée et aucun balayage Internet");
            foreach(var excluded in new[]{"192.168.1.0","192.168.1.255","192.168.1.70","127.0.0.1","224.0.0.1"}) Check(!LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse(excluded)),"Adresse exclue de la découverte directe : "+excluded);
            foreach(string invalid in new[]{"file:///C:/secret","http://user:pass@localhost","http://localhost/private","http://localhost/?secret=1","8420"}) {
                bool rejected=false; try { LibraryClient.NormalizeServer(invalid); } catch(ArgumentException) { rejected=true; } Check(rejected,"Adresse refusée : "+invalid);
            }
            bool badId=false; try { LibraryClient.ItemPath("../config.json"); } catch(ArgumentException) { badId=true; } Check(badId,"Chemin de média limité à un identifiant");
            Check(Native.Quote("C:\\Photos de famille\\")=="\"C:\\Photos de famille\\\\\"","Arguments Windows avec espaces et antislash final");
            Check(File.Exists(Path.Combine(Native.Root,"ui","reader.js")),"Interface du lecteur présente");
            Check(SeekAcceleration.Distance(0)==10 && SeekAcceleration.Distance(12000)==1024 && SeekAcceleration.Speed(18000)=="×64","Avance progressive de dix secondes à ×64");
            var buffering=new PlaybackBufferPolicy();
            Check(buffering.ResumeSeconds==3 && !buffering.Observe(true,false,true),"Chargement initial et seek exclus de l'adaptation réseau");
            buffering.Observe(false,false,false);
            Check(buffering.Observe(true,false,false) && buffering.ResumeSeconds==6 && !buffering.Observe(true,false,false),"Une coupure augmente la réserve une seule fois");
            for(int i=0;i<10;i++) { buffering.Observe(false,false,false); buffering.Observe(true,false,false); }
            Check(buffering.ResumeSeconds==20,"Réserve Windows progressive et bornée");
            if(Array.IndexOf(args,"--test-bootstrap")>=0) {
                using(var controller=new ServerController()) {
                    await controller.Start(message=>{});
                    Check(controller.Owned,"Serveur préparé et démarré sans console");
                    using(var api=new LibraryClient()) await api.Connect(controller.Address);
                    controller.Stop();
                    Check(!controller.Owned,"Arrêt du serveur lancé par l'application");
                }
            }
            string server=Argument(args,"--test-server");
            if(server!=null) {
                using(var api=new LibraryClient()) {
                    await api.Connect(server); var page=Native.Object(await api.Api("page",new Dictionary<string,object>{{"kind","movie"}}));
                    Check(page.ContainsKey("items"),"Connexion au serveur et catalogue paginé");
                }
            }
            string discover=Argument(args,"--test-discovery");
            if(discover!=null) {
                using(var api=new LibraryClient()) {
                    var found=Native.Json.DeserializeObject(Native.Json.Serialize(await api.Discover())) as object[];
                    Check(found!=null && Array.Exists(found, item=>Native.Text(Native.Object(item),"url")==LibraryClient.NormalizeServer(discover)),"Serveur Android trouvé automatiquement sur le réseau local");
                }
            }
            string url=Argument(args,"--video");
            if(url!=null) {
                using(var player=new MpvSession()) {
                    await player.Start(url,IntPtr.Zero,"auto",0,true);
                    Check(Convert.ToInt64(await player.Command("get_property","demuxer-max-bytes"))==256L*1024*1024,"Cache vidéo Windows plafonné à 256 MiB");
                    await player.Command("set_property","cache-pause-wait",buffering.ResumeSeconds);
                    Check(Convert.ToInt32(await player.Command("get_property","cache-pause-wait"))==20,"Réserve de reprise modifiable pendant la lecture");
                    double duration=0; for(int i=0;i<60;i++) { try { duration=Convert.ToDouble(await player.Command("get_property","duration")); if(duration>0) break; } catch(IOException) { } await Task.Delay(250); }
                    Check(duration>5,"Vidéo originale ouverte par le moteur natif");
                    await player.Command("set_property","pause",true);
                    string seek=Argument(args,"--seek"); double target=Math.Min(seek==null?25:double.Parse(seek,System.Globalization.CultureInfo.InvariantCulture),duration-.5); await player.Command("seek",target,"absolute+exact");
                    double position=await WaitPosition(player,target); Check(Math.Abs(position-target)<1,"Déplacement absolu sans retour au début");
                    double delta=Math.Min(10,duration*.1); await player.Command("seek",-delta,"relative+exact");
                    position=await WaitPosition(player,target-delta); Check(Math.Abs(position-(target-delta))<1,"Retour relatif dans le fichier original");
                    string height=Argument(args,"--height"); if(height!=null) Check(Convert.ToInt32(await player.Command("get_property","height"))==int.Parse(height),"Résolution vidéo originale conservée : "+height);
                }
            }
        }
    }
}
