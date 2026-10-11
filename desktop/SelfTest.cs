using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;

namespace Pluxy {
    internal static class SelfTest {
        private static async Task VerifyProgressPersistence() {
            var path=Path.Combine(Path.GetTempPath(), "Pluxy-outbox-"+Guid.NewGuid().ToString("N")+".json");
            var old=new ProgressUpdate { server="http://127.0.0.1:18442",profile="default",id="film",session="session00",sequence=1,positionMs=600000,durationMs=1200000 };
            try {
                using(var queue=new ProgressOutbox(path,u=>Task.FromException(new IOException("offline")),false)) { queue.Enqueue(old); await queue.Drain(); }
                using(var restored=new ProgressOutbox(path,u=>Task.FromResult(true),false)) {
                    Check(restored.Get(old.server,"default","film").positionMs==600000,"Une coupure et un redémarrage Windows conservent la reprise");
                }
                var sent=new TaskCompletionSource<bool>(); var finish=new TaskCompletionSource<bool>();
                using(var queue=new ProgressOutbox(path,async u=>{sent.TrySetResult(true);await finish.Task;},false)) {
                    var drain=queue.Drain(); await sent.Task;
                    var newer=new ProgressUpdate {server=old.server,id=old.id,session=old.session,sequence=2,positionMs=10000,durationMs=old.durationMs};
                    queue.Enqueue(newer); finish.SetResult(true); await drain;
                    Check(queue.Get(old.server,"default","film").positionMs==10000,"Un accusé ancien ne supprime pas un retour volontaire dans le média");
                    queue.Enqueue(old);
                    Check(queue.Get(old.server,"default","film").sequence==2,"Une sauvegarde ancienne ne remplace pas une nouvelle progression");
                }
                using(var queue=new ProgressOutbox(path,u=>Task.FromResult(true),false)) {
                    queue.Enqueue(new ProgressUpdate {server=old.server,id=old.id,profile=new string('a',32),session="session01",sequence=1,positionMs=45000,durationMs=old.durationMs});
                    Check(queue.Get(old.server,new string('a',32),"film").positionMs==45000 && queue.Get(old.server,"default","film").positionMs==10000,"Les progressions Windows en attente restent séparées par profil");
                    await queue.Drain(); Check(queue.Get(old.server,"default","film")==null,"Une reprise confirmée est retirée du journal");
                }
            } finally { if(File.Exists(path)) File.Delete(path); }
        }
        private static void VerifyBackupSafety() {
            var prefs=new Preferences {audio="passthrough",theme="ocean",playerFocusContrast=true};
            var address="http://127.0.0.1:18443";
            prefs.profiles[address]=new string('a',32);
            Check(prefs.ProfileFor(address)=="default","Le profil Général est utilisé par défaut même si un profil ancien existe");
            prefs.multipleProfiles=true;
            Check(prefs.ProfileFor(address)==new string('a',32),"Les profils séparés nécessitent une activation explicite");
            prefs.multipleProfiles=false;
            Check(prefs.ProfileFor(address)=="default" && prefs.profiles[address]==new string('a',32),"Désactiver plusieurs profils retrouve Général sans supprimer les autres profils");
            prefs.StoreReader("http://127.0.0.1:18443","default"); prefs.ApplyReader("http://127.0.0.1:18443",new string('a',32));
            Check(prefs.audio=="auto" && prefs.theme=="coral" && !prefs.playerFocusContrast,"Un nouveau profil reçoit ses propres réglages par défaut");
            prefs.ApplyReader("http://127.0.0.1:18443","default");
            Check(prefs.audio=="passthrough" && prefs.theme=="ocean" && prefs.playerFocusContrast,"Le profil partagé retrouve ses réglages de lecture");
            Check(SettingsBackup.Allowed("profiles/"+new string('a',32)+"/.pluxy_watch.json") && !SettingsBackup.Allowed("../config.json") && !SettingsBackup.Allowed("tools/Start-PC.ps1") && !SettingsBackup.Allowed("profiles/../../config.json"),"Les sauvegardes refusent chemins sortants et fichiers exécutables");
            var configuration=new Dictionary<string,object> { {"metadata",new Dictionary<string,object>{{"tmdb_api_key","test-secret"},{"language","fr"}}},{"server",new Dictionary<string,object>{{"port",8420}}} };
            var clean=Native.Object(SettingsBackup.WithoutSecrets(configuration));
            Check(!Native.Object(clean["metadata"]).ContainsKey("tmdb_api_key") && Native.Text(Native.Object(clean["metadata"]),"language")=="fr","Les sauvegardes excluent les clés API et conservent les réglages");
            var path=Path.Combine(Path.GetTempPath(),"Pluxy-backup-test-"+Guid.NewGuid().ToString("N")+".zip");
            try {
                using(var zip=System.IO.Compression.ZipFile.Open(path,System.IO.Compression.ZipArchiveMode.Create))
                using(var output=new StreamWriter(zip.CreateEntry("desktop.json").Open())) output.Write("[]");
                bool rejected=false; try { SettingsBackup.Read(path); } catch(IOException) { rejected=true; }
                Check(rejected,"Une sauvegarde mal formée est refusée avant toute restauration");
                File.Delete(path);
                using(var zip=System.IO.Compression.ZipFile.Open(path,System.IO.Compression.ZipArchiveMode.Create)) {
                    using(var output=new StreamWriter(zip.CreateEntry("desktop.json").Open())) output.Write("{}");
                    using(var output=new StreamWriter(zip.CreateEntry(".pluxy_watch.json").Open())) { output.Write("{\"sample\":\""); output.Write(new string('x',17*1024*1024)); output.Write("\"}"); }
                }
                Check(SettingsBackup.Read(path)[".pluxy_watch.json"].Length>16*1024*1024,"Une sauvegarde volumineuse reste lisible dans la limite de 64 Mo");
            } finally { if(File.Exists(path)) File.Delete(path); }
        }
        private static void VerifySkipSegments() {
            object[] chapters={new Dictionary<string,object>{{"title","Opening"},{"start_ms",10000},{"end_ms",40000}},
                new Dictionary<string,object>{{"title","Générique de fin"},{"start_ms",900000},{"end_ms",950000}},
                new Dictionary<string,object>{{"title","Post credits"},{"start_ms",950000},{"end_ms",1000000}}};
            var detected=SkipSegments.Detect(chapters,1000000);
            Check(detected.Count==2 && SkipSegments.Active(detected,5000)==null && SkipSegments.Active(detected,10000).endMs==40000 && SkipSegments.Active(detected,900000).endMs==950000 && SkipSegments.Active(detected,950000)==null,"Les chapitres identifiés conservent la scène avant l'intro et celle après le générique");
            Check(SkipSegments.Detect(new object[]{new Dictionary<string,object>{{"title","Chapter 1"},{"start_ms",0},{"end_ms",40000}},new Dictionary<string,object>{{"title","Intro"},{"start_ms",0},{"end_ms",900000}}},1000000).Count==0,"Aucune scène n'est devinée à partir de chapitres ordinaires ou de durées incohérentes");
            var merged=SkipSegments.Merge(detected,new SkipMarkers {introStartMs=15000,introEndMs=50000}.Segments(1000000));
            Check(merged.Count==2 && SkipSegments.Active(merged,20000).endMs==50000,"Le repère manuel remplace l'intro détectée et conserve le générique");
            var precise=SkipSegments.Resolve(new List<SkipSegment>{new SkipSegment{kind="intro",startMs=120000,endMs=180000}},new List<SkipSegment>(),new List<SkipSegment>{new SkipSegment{kind="intro",startMs=0,endMs=60000}});
            Check(SkipSegments.Active(precise,10000)==null && SkipSegments.Active(precise,125000).endMs==180000,"Les chapitres propres à l'épisode priment sur un modèle de saison et préservent une scène d'ouverture différente");
            var path=Path.Combine(Path.GetTempPath(),"Pluxy-skip-"+Guid.NewGuid().ToString("N")+".json");
            var episode=new Dictionary<string,object>{{"id","episode1"},{"kind","series"},{"series_key","test"},{"source_id","source"},{"season",1}};
            var following=new Dictionary<string,object>(episode); following["id"]="episode2";
            try {
                var store=new SkipMarkerStore(path);
                store.Save("http://server:8420",episode,new SkipMarkers {introStartMs=10000,introEndMs=40000,creditsStartMs=900000,creditsEndMs=950000});
                var restored=new SkipMarkerStore(path);
                Check(restored.Effective("http://server:8420",episode).creditsEndMs==950000,"Les repères locaux survivent au redémarrage");
                restored.Share("http://server:8420",episode,restored.Own("http://server:8420",episode));
                Check(restored.Effective("http://server:8420",following).introEndMs==40000 && restored.Effective("http://server:8420",following).creditsStartMs==null,"La réutilisation par saison partage uniquement l'intro, jamais le générique variable");
                following["season"]=2;
                Check(restored.Effective("http://server:8420",following).introEndMs==null && restored.Effective("http://other:8420",episode).introEndMs==null,"Les repères restent séparés entre saisons et serveurs");
                restored.Clear("http://server:8420",episode); restored.ClearSeason("http://server:8420",episode);
                Check(restored.Effective("http://server:8420",episode).Segments(1000000).Count==0,"Les repères manuels et ceux d'une saison peuvent être effacés");
            } finally { if(File.Exists(path)) File.Delete(path); }
        }
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
            VerifySkipSegments();
            var skipFile=Argument(args,"--test-skip");
            if(skipFile!=null) using(var player=new MpvSession()) {
                await player.Start(skipFile,IntPtr.Zero,"compatible",0,true);
                double length=Convert.ToDouble(await player.Command("get_property","duration"));
                var segments=SkipSegments.Detect(SkipSegments.Chapters(await player.Command("get_property","chapter-list") as object[],length),(long)(length*1000));
                Check(segments.Count==2 && SkipSegments.Active(segments,1000)==null,"Les chapitres réels du fichier sont disponibles sans service externe");
                var intro=SkipSegments.Active(segments,4000);
                await player.Command("seek",intro.endMs/1000.0,"absolute+exact");
                Check(Math.Abs(await WaitPosition(player,6)-6)<1,"Passer l'intro atteint sa fin sans revenir au début");
                var credits=SkipSegments.Active(segments,19000);
                await player.Command("seek",credits.endMs/1000.0,"absolute+exact");
                double position=await WaitPosition(player,21);
                Check(position>=20 && position<length-1 && !Convert.ToBoolean(await player.Command("get_property","eof-reached")),"Passer le générique conserve la scène après le générique");
            }
            Check(LibraryClient.NormalizeServer("192.168.1.20:8420")=="http://192.168.1.20:8420","Adresse du serveur normalisée");
            Check(LibraryClient.NormalizeServer("192.168.1.20")=="http://192.168.1.20:8420" && LibraryClient.NormalizeServer("http://192.168.1.20")=="http://192.168.1.20:8420","Port Pluxy ajouté à une adresse IP seule");
            Check(LibraryClient.NormalizeServer("http://192.168.1.20:80")=="http://192.168.1.20" && LibraryClient.NormalizeServer("https://192.168.1.20")=="https://192.168.1.20","Ports explicitement choisis conservés");
            var lan=System.Net.IPAddress.Parse("192.168.1.70"); var mask=System.Net.IPAddress.Parse("255.255.255.0");
            Check(LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse("192.168.1.104")) && !LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse("172.18.11.218")),"Découverte directe limitée au sous-réseau local");
            var candidates=LanDiscoveryTargets.SubnetCandidates(lan,mask);
            Check(candidates.Count==253 && candidates.Contains(System.Net.IPAddress.Parse("192.168.1.104")),"Découverte d'un Android absent du cache réseau");
            Check(LanDiscoveryTargets.SubnetCandidates(lan,System.Net.IPAddress.Parse("255.255.255.224")).Count==29 && LanDiscoveryTargets.SubnetCandidates(System.Net.IPAddress.Parse("10.20.30.40"),System.Net.IPAddress.Parse("255.255.0.0")).Count<=256 && LanDiscoveryTargets.SubnetCandidates(System.Net.IPAddress.Parse("8.8.8.8"),mask).Count==0,"Recherche locale bornée et aucun balayage Internet");
            using(var receiver=LibraryClient.DiscoverySocket())
            using(var peer=new System.Net.Sockets.UdpClient(new System.Net.IPEndPoint(System.Net.IPAddress.Loopback,0))) {
                int closedPort;
                using(var closed=new System.Net.Sockets.UdpClient(new System.Net.IPEndPoint(System.Net.IPAddress.Loopback,0))) closedPort=((System.Net.IPEndPoint)closed.Client.LocalEndPoint).Port;
                var message=System.Text.Encoding.UTF8.GetBytes("PLUXY_DISCOVERY_V1");
                await receiver.SendAsync(message,message.Length,new System.Net.IPEndPoint(System.Net.IPAddress.Loopback,closedPort));
                await Task.Delay(100);
                await peer.SendAsync(message,message.Length,new System.Net.IPEndPoint(System.Net.IPAddress.Loopback,((System.Net.IPEndPoint)receiver.Client.LocalEndPoint).Port));
                var receive=receiver.ReceiveAsync();
                Check(await Task.WhenAny(receive,Task.Delay(2000))==receive && System.Text.Encoding.UTF8.GetString((await receive).Buffer)=="PLUXY_DISCOVERY_V1","Un port UDP fermé n'interrompt pas la découverte Windows");
            }
            foreach(var excluded in new[]{"192.168.1.0","192.168.1.255","192.168.1.70","127.0.0.1","224.0.0.1"}) Check(!LanDiscoveryTargets.SameSubnet(lan,mask,System.Net.IPAddress.Parse(excluded)),"Adresse exclue de la découverte directe : "+excluded);
            foreach(string invalid in new[]{"file:///C:/secret","http://user:pass@localhost","http://localhost/private","http://localhost/?secret=1","8420"}) {
                bool rejected=false; try { LibraryClient.NormalizeServer(invalid); } catch(ArgumentException) { rejected=true; } Check(rejected,"Adresse refusée : "+invalid);
            }
            bool badId=false; try { LibraryClient.ItemPath("../config.json"); } catch(ArgumentException) { badId=true; } Check(badId,"Chemin de média limité à un identifiant");
            Check(Native.Quote("C:\\Photos de famille\\")=="\"C:\\Photos de famille\\\\\"","Arguments Windows avec espaces et antislash final");
            Check(File.Exists(Path.Combine(Native.Root,"ui","reader.js")),"Interface du lecteur présente");
            var technicalEpisode=new Dictionary<string,object> { {"kind","series"},{"title","Rick.S01E01.MULTI.1080p.WEB-DL.H264-FTMVHD"},{"series_name","Rick et Morty"},{"season",1},{"episode",1} };
            Check(MediaPresentation.EpisodeTitle(technicalEpisode)=="Épisode 1" && MediaPresentation.PlaybackTitle(technicalEpisode)=="Rick et Morty · Saison 1 - Épisode 1","Titres techniques d'épisode remplacés par un libellé lisible");
            technicalEpisode["title"]="Rick.S01E01.La Summer de toutes les peurs x265-Amen";
            Check(MediaPresentation.EpisodeTitle(technicalEpisode)=="La Summer de toutes les peurs","Titre réel de l'épisode conservé sans codec ni groupe");
            Check(MediaPresentation.EpisodeCode(new Dictionary<string,object>{{"season",0},{"episode",3}})=="Saison 0 - Épisode 3" && MediaPresentation.EpisodeCode(new Dictionary<string,object>{{"episode",3}})=="Épisode 3","Numérotation lisible pour les saisons spéciales et les épisodes sans saison");
            Check(!Native.Json.Deserialize<Preferences>("{}").playerActionHints,"Les libellés des commandes sont désactivés par défaut, y compris avec les anciennes préférences");
            await VerifyProgressPersistence();
            VerifyBackupSafety();
            VerifyPlayerGlyphs();
            Check(PlayerWindow.AudioDeviceLabel("auto","Autoselect device")=="Sortie automatique" && PlayerWindow.AudioDeviceLabel("openal/default","Default (openal)")=="Sortie par défaut (openal)","Sorties audio automatiques présentées en français");
            Check(PlayerWindow.AudioDeviceLabel("wasapi/device","Casque personnel")=="Casque personnel","Nom du périphérique audio conservé");
            var originalContext=System.Threading.SynchronizationContext.Current;
            using(var api=new LibraryClient()) using(var window=new PlayerWindow(api,"test","Test","http://127.0.0.1:1/stream/direct/test","auto",0,null,new object[]{new Dictionary<string,object>{{"id","test"},{"season",1},{"episode",1}},new Dictionary<string,object>{{"id","next"},{"season",1},{"episode",2}}})) {
                var flags=System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic;
                window.Size=window.MinimumSize;
                window.PerformLayout();
                var actions=(System.Windows.Forms.FlowLayoutPanel)typeof(PlayerWindow).GetField("actionButtons",flags).GetValue(window);
                actions.PerformLayout();
                bool actionsFit=true;
                foreach(System.Windows.Forms.Control control in actions.Controls) actionsFit &= control.Left>=0 && control.Top>=0 && control.Right<=actions.ClientSize.Width && control.Bottom<=actions.ClientSize.Height;
                Check(actionsFit,"Toutes les commandes restent accessibles dans la plus petite fenêtre, y compris l'épisode suivant");
                var slider=(PlayerSlider)typeof(PlayerWindow).GetField("timeline",flags).GetValue(window);
                window.ActiveControl=slider;
                var message=System.Windows.Forms.Message.Create(window.Handle,0,System.IntPtr.Zero,System.IntPtr.Zero);
                var route=typeof(PlayerWindow).GetMethod("ProcessCmdKey",flags);
                route.Invoke(window,new object[]{message,System.Windows.Forms.Keys.F11});
                Check(window.FormBorderStyle==System.Windows.Forms.FormBorderStyle.None,"F11 reste disponible avec le curseur de position sélectionné");
                route.Invoke(window,new object[]{message,System.Windows.Forms.Keys.Escape});
                Check(window.FormBorderStyle==System.Windows.Forms.FormBorderStyle.Sizable,"Échap quitte le plein écran depuis le curseur de position");
                typeof(PlayerSlider).GetMethod("OnKeyDown",flags).Invoke(slider,new object[]{new System.Windows.Forms.KeyEventArgs(System.Windows.Forms.Keys.Right)});
                Check((bool)typeof(PlayerWindow).GetField("dragging",flags).GetValue(window),"Le réglage au clavier suspend l'actualisation de la position");
                typeof(System.Windows.Forms.Control).GetMethod("OnKeyUp",flags).Invoke(slider,new object[]{new System.Windows.Forms.KeyEventArgs(System.Windows.Forms.Keys.Right)});
                Check(!(bool)typeof(PlayerWindow).GetField("dragging",flags).GetValue(window),"Le relâchement du curseur rétablit l'actualisation");
            }
            System.Threading.SynchronizationContext.SetSynchronizationContext(originalContext);
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
            if(Argument(args,"--test-music")!=null) {
                using(var api=new LibraryClient()) {
                    await api.Connect(server);
                    var page=Native.Object(await api.Api("page",new Dictionary<string,object>{{"kind","audio"},{"q","Audit music"}}));
                    var tracks=page["items"] as object[];
                    Check(tracks!=null && tracks.Length==2,"Deux pistes musicales de test disponibles");
                    IDictionary<string,object> last=null;
                    using(var music=new MusicSession(api,value=>last=Native.Object(Native.Json.DeserializeObject(Native.Json.Serialize(value))),false)) {
                        await music.Play(Native.Object(tracks[0]),tracks,"compatible");
                        await music.Control("pause"); await Task.Delay(600); await music.Poll();
                        Check(Native.Number(last,"duration")>1,"Lecture musicale native sans fenêtre vidéo");
                        await music.Control("seek",1); await Task.Delay(200); await music.Poll();
                        Check(Native.Number(last,"position")>=.8,"Déplacement dans une piste musicale");
                        await music.Control("next");
                        Check(Native.Text(last,"id")==Native.Text(Native.Object(tracks[1]),"id"),"La file musicale passe à la piste suivante");
                        await music.Control("previous");
                        Check(Native.Text(last,"id")==Native.Text(Native.Object(tracks[0]),"id"),"La file musicale revient à la piste précédente");
                        await music.Poll(); await music.Control("pause");
                        await music.Control("seek",Native.Number(last,"duration")); await Task.Delay(500); await music.Poll();
                        Check(Native.Text(last,"id")==Native.Text(Native.Object(tracks[1]),"id"),"Enchaînement musical automatique à la fin de la piste");
                        await music.Stop(); Check(!Convert.ToBoolean(last["visible"]),"Arrêt du mini lecteur musical");
                    }
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
        private static void VerifyPlayerGlyphs() {
            var originalContext=System.Threading.SynchronizationContext.Current;
            try {
            bool stable=true,proportional=true,unframed=true,glowing=false;
            var enter=typeof(PlayerButton).GetMethod("OnMouseEnter",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic);
            foreach(string symbol in new[]{"play","pause","restart","back","forward","audio","subtitles","more","close","fullscreen","next"}) {
                foreach(int size in new[]{40,44,88,132}) {
                    using(var button=new PlayerButton { Symbol=symbol,Size=new System.Drawing.Size(size,size) }) {
                        var normal=GlyphBounds(button);
                        enter.Invoke(button,new object[]{EventArgs.Empty});
                        var selected=GlyphBounds(button);
                        var illuminated=GlyphBounds(button,3);
                        glowing |= illuminated.Width>selected.Width || illuminated.Height>selected.Height;
                        stable &= !normal.IsEmpty && Math.Abs(normal.Width-selected.Width)<=1 && Math.Abs(normal.Height-selected.Height)<=1 && Math.Abs(normal.Left-selected.Left)<=1 && Math.Abs(normal.Top-selected.Top)<=1;
                        unframed &= selected.Left>3 && selected.Top>3 && selected.Right<size-3 && selected.Bottom<size-3;
                        button.Size=new System.Drawing.Size(size,size+24);
                        var tall=GlyphBounds(button);
                        button.Size=new System.Drawing.Size(size+24,size);
                        var wide=GlyphBounds(button);
                        proportional &= selected.Size==tall.Size && selected.Size==wide.Size;
                    }
                }
            }
            Check(stable,"Onze pictogrammes gardent leur forme au focus, de 100 % à 300 %");
            Check(proportional,"Les boutons rectangulaires ne déforment pas les pictogrammes");
            Check(unframed,"La sélection des pictogrammes ne dessine aucun cadre autour du bouton");
            Check(glowing,"Le halo reste distinct du tracé net de l'icône");
            } finally { System.Threading.SynchronizationContext.SetSynchronizationContext(originalContext); }
        }
        private static System.Drawing.Rectangle GlyphBounds(PlayerButton button,int threshold=70) {
            int left=button.Width,top=button.Height,right=-1,bottom=-1;
            using(var image=new System.Drawing.Bitmap(button.Width,button.Height)) {
                button.DrawToBitmap(image,button.ClientRectangle);
                for(int y=0;y<image.Height;y++) for(int x=0;x<image.Width;x++) {
                    var color=image.GetPixel(x,y); var background=button.BackColor;
                    if(Math.Abs(color.R-background.R)+Math.Abs(color.G-background.G)+Math.Abs(color.B-background.B)<=threshold) continue;
                    left=Math.Min(left,x);right=Math.Max(right,x);top=Math.Min(top,y);bottom=Math.Max(bottom,y);
                }
            }
            return right<left?System.Drawing.Rectangle.Empty:System.Drawing.Rectangle.FromLTRB(left,top,right+1,bottom+1);
        }
    }
}
