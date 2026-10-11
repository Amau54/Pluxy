using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Pluxy {
    internal sealed class ProgressUpdate {
        public string server, id, session;
        public string profile="default";
        public long sequence, positionMs, durationMs;
        public string Key { get { return server + "\n" + profile + "\n" + id; } }
    }

    // Acknowledging an older request must never remove a newer seek.
    internal sealed class ProgressOutbox : IDisposable {
        private readonly object gate = new object();
        private readonly string path;
        private readonly Func<ProgressUpdate,Task> send;
        private readonly Dictionary<string,ProgressUpdate> pending = new Dictionary<string,ProgressUpdate>();
        private readonly Dictionary<string,ProgressUpdate> latest = new Dictionary<string,ProgressUpdate>();
        private readonly Dictionary<string,DateTime> retries = new Dictionary<string,DateTime>();
        private readonly System.Threading.Timer timer;
        private int draining;
        private bool disposed;
        private bool loaded;
        private bool dirty;
        private bool paused;
        public ProgressOutbox(string path,Func<ProgressUpdate,Task> send,bool automatic=true) {
            this.path=path; this.send=send;
            try { Load(); } catch(Exception error) { Native.Log(error); }
            if(automatic) timer=new System.Threading.Timer(async _=>await Drain(),null,1000,5000);
        }
        private void Load() {
            if(loaded) return;
            if(File.Exists(path)) {
                ProgressUpdate[] records;
                try {
                    if(new FileInfo(path).Length>1024*1024) throw new ArgumentException("Journal trop volumineux.");
                    records=Native.Json.Deserialize<ProgressUpdate[]>(File.ReadAllText(path)) ?? new ProgressUpdate[0];
                    if(records.Length>128) throw new ArgumentException("Journal trop volumineux.");
                    foreach(var record in records) {
                        if(record==null) throw new ArgumentException("Journal invalide.");
                        record.profile=record.profile ?? "default";
                        if(record.durationMs<=0 || record.positionMs<0 || record.sequence<0 || !System.Text.RegularExpressions.Regex.IsMatch(record.session ?? "","^[A-Za-z0-9_-]{8,64}$") || !System.Text.RegularExpressions.Regex.IsMatch(record.profile ?? "default","^(default|[0-9a-f]{32})$")) throw new ArgumentException("Journal invalide.");
                        record.server=LibraryClient.NormalizeServer(record.server); LibraryClient.ItemPath(record.id);
                    }
                } catch(Exception error) {
                    if(error is IOException || error is UnauthorizedAccessException) throw;
                    File.Move(path,path+".invalid-"+Guid.NewGuid().ToString("N")); records=new ProgressUpdate[0]; Native.Log(error);
                }
                foreach(var record in records ?? new ProgressUpdate[0]) {
                    pending[record.Key]=record; latest[record.Key]=record;
                }
            }
            loaded=true;
        }
        public void Enqueue(ProgressUpdate update) {
            lock(gate) {
                Load();
                ProgressUpdate old;
                if(latest.TryGetValue(update.Key,out old) && old.session==update.session && old.sequence>=update.sequence) return;
                var next=new Dictionary<string,ProgressUpdate>(pending); next[update.Key]=update;
                if(next.Count>128) throw new IOException("Trop de reprises en attente. Reconnectez le serveur pour les synchroniser.");
                try { Publish(next); }
                catch(IOException) { pending.Clear(); foreach(var pair in next) pending[pair.Key]=pair.Value; dirty=true; throw; }
                finally { latest[update.Key]=update; }
            }
        }
        public ProgressUpdate Get(string server,string profile,string id) { lock(gate) { Load(); ProgressUpdate value; return pending.TryGetValue(server+"\n"+profile+"\n"+id,out value)?value:null; } }
        private void Publish(Dictionary<string,ProgressUpdate> values) {
            Native.WriteAtomic(path,Native.Json.Serialize(values.Values.ToArray()));
            dirty=false;
            pending.Clear(); foreach(var pair in values) pending[pair.Key]=pair.Value;
        }
        internal async Task Drain() {
            if(disposed || paused || Interlocked.Exchange(ref draining,1)!=0) return;
            try {
                ProgressUpdate[] batch;
                lock(gate) {
                    Load();
                    if(dirty) { Native.WriteAtomic(path,Native.Json.Serialize(pending.Values.ToArray())); dirty=false; }
                    batch=pending.Values.Where(value=>!retries.ContainsKey(value.Key) || retries[value.Key]<=DateTime.UtcNow).Take(8).ToArray();
                }
                foreach(var update in batch) {
                    if(disposed || paused) break;
                    try {
                        await send(update).ConfigureAwait(false);
                        lock(gate) {
                            ProgressUpdate current;
                            if(pending.TryGetValue(update.Key,out current) && ReferenceEquals(current,update)) {
                                var next=new Dictionary<string,ProgressUpdate>(pending); next.Remove(update.Key); Publish(next);
                            }
                            retries.Remove(update.Key);
                        }
                    } catch(Exception error) { lock(gate) retries[update.Key]=DateTime.UtcNow.AddSeconds(30); Native.Log(error); }
                }
            } catch(Exception error) { Native.Log(error); }
            finally { Interlocked.Exchange(ref draining,0); }
        }
        public void Dispose() { disposed=true; if(timer!=null) timer.Dispose(); }
        public async Task Suspend() {
            paused=true;
            while(Volatile.Read(ref draining)!=0) await Task.Delay(20);
        }
        public void Resume() { paused=false; }
    }
}
