using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class MpvSession : IDisposable {
        private Process process;
        private NamedPipeClientStream pipe;
        private StreamReader reader;
        private StreamWriter writer;
        private SemaphoreSlim gate=new SemaphoreSlim(1,1);
        private int requestId;
        private bool disposed;
        public async Task Start(string url,IntPtr host,string audio,double position,bool headless=false) {
            var executable=Path.Combine(Native.Root,"media","mpv.exe");
            if(!File.Exists(executable)) throw new IOException("Le moteur de lecture manque. Réinstallez Pluxy.");
            var name="pluxy-"+Guid.NewGuid().ToString("N");
            var arguments=new List<string>{"--no-config","--no-terminal","--idle=yes","--keep-open=yes","--osc=no","--input-default-bindings=no","--input-ipc-server=\\\\.\\pipe\\"+name,"--hwdec=auto-safe","--vo=gpu-next","--gpu-api=d3d11","--target-colorspace-hint=yes","--cache=yes","--cache-secs=120","--cache-pause=yes","--cache-pause-initial=yes","--cache-pause-wait=3","--demuxer-max-bytes=256MiB","--demuxer-max-back-bytes=16MiB","--volume-max=100"};
            if(headless) { arguments.Add("--vo=null"); arguments.Add("--ao=null"); arguments.Add("--pause=yes"); }
            else { arguments.Add("--wid="+unchecked((uint)host.ToInt64())); arguments.Add("--ao=wasapi"); }
            if(audio=="passthrough") { arguments.Add("--audio-spdif=ac3,dts,dts-hd,eac3,truehd"); arguments.Add("--audio-exclusive=yes"); }
            else if(audio=="compatible") arguments.Add("--audio-channels=stereo");
            arguments.Add("--start="+position.ToString("0.###",System.Globalization.CultureInfo.InvariantCulture));
            arguments.Add("--"); arguments.Add(url);
            process=Process.Start(Native.StartInfo(executable,arguments));
            for(int attempt=0;attempt<30;attempt++) {
                if(process.HasExited) throw new IOException("Le moteur vidéo n'a pas démarré.");
                var candidate=new NamedPipeClientStream(".",name,PipeDirection.InOut,PipeOptions.Asynchronous);
                try { await Task.Run(()=>candidate.Connect(250)); pipe=candidate; break; }
                catch(TimeoutException) { candidate.Dispose(); }
                if(disposed) throw new ObjectDisposedException("MpvSession");
            }
            if(pipe==null) throw new IOException("Le lecteur vidéo ne répond pas.");
            reader=new StreamReader(pipe,new UTF8Encoding(false),true,4096,true);
            writer=new StreamWriter(pipe,new UTF8Encoding(false),4096,true) { AutoFlush=true };
            // L'ouverture du pipe précède celle du média : les commandes de seek
            // ne sont disponibles qu'après l'initialisation des pistes.
            for(int attempt=0;attempt<120;attempt++) {
                try { if(await Command("get_property","time-pos")!=null) return; }
                catch(IOException) { if(disposed || !pipe.IsConnected) throw; }
                await Task.Delay(250);
            }
            throw new IOException("La vidéo n'a pas pu être ouverte. Vérifiez le fichier et le serveur.");
        }
        public async Task<object> Command(params object[] command) {
            if(disposed || pipe==null) throw new IOException("Le lecteur est fermé.");
            await gate.WaitAsync();
            try {
                int id=++requestId; await writer.WriteLineAsync(Native.Json.Serialize(new{command=command,request_id=id}));
                var deadline=DateTime.UtcNow.AddSeconds(4);
                while(DateTime.UtcNow<deadline) {
                    var line=reader.ReadLineAsync(); if(await Task.WhenAny(line,Task.Delay(Math.Max(1,(int)(deadline-DateTime.UtcNow).TotalMilliseconds)))!=line) { pipe.Dispose(); throw new IOException("Le lecteur ne répond plus."); }
                    var text=await line; if(text==null || text.Length>1024*1024) throw new IOException("La lecture a été interrompue.");
                    var result=Native.Object(Native.Json.DeserializeObject(text));
                    if((int)Native.Number(result,"request_id",-1)!=id) continue;
                    if(Native.Text(result,"error")!="success") throw new IOException(string.Join(" ",Array.ConvertAll(command,value=>Convert.ToString(value)))+" : "+Native.Text(result,"error","Commande vidéo refusée."));
                    object data; return result.TryGetValue("data",out data)?data:null;
                }
                throw new IOException("Le lecteur ne répond plus.");
            } finally { gate.Release(); }
        }
        public void Dispose() { if(disposed) return; disposed=true; if(pipe!=null) pipe.Dispose(); ServerController.StopProcess(process); if(process!=null) process.Dispose(); }
    }
}
