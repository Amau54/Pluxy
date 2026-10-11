using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using System.Drawing;
using System.Runtime.InteropServices;

namespace Pluxy {
    internal static class Native {
        public static JavaScriptSerializer Json { get { return new JavaScriptSerializer { MaxJsonLength = 20 * 1024 * 1024 }; } }
        public static string Root = AppDomain.CurrentDomain.BaseDirectory;
        public static string Data = Path.Combine(Environment.GetEnvironmentVariable("PLUXY_DATA_DIR") ?? Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pluxy");
        public static string InstanceName { get { using(var hash=System.Security.Cryptography.SHA256.Create()) return "Local\\PluxyDesktop"+BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(Data.ToLowerInvariant()))).Replace("-","").Substring(0,16); } }
        public static IDictionary<string,object> Object(object value) { return value as IDictionary<string,object> ?? new Dictionary<string,object>(); }
        public static string Text(IDictionary<string,object> value, string key, string fallback = "") {
            object result; return value.TryGetValue(key, out result) && result != null ? Convert.ToString(result, System.Globalization.CultureInfo.InvariantCulture) : fallback;
        }
        public static double Number(IDictionary<string,object> value, string key, double fallback = 0) {
            object result; double number;
            return value.TryGetValue(key, out result) && double.TryParse(Convert.ToString(result, System.Globalization.CultureInfo.InvariantCulture), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out number) ? number : fallback;
        }
        public static string Quote(string value) {
            return "\"" + System.Text.RegularExpressions.Regex.Replace(System.Text.RegularExpressions.Regex.Replace(value, "(\\\\*)\"", "$1$1\\\""), "(\\\\+)$", "$1$1") + "\"";
        }
        public static ProcessStartInfo StartInfo(string executable, IEnumerable<string> arguments) {
            var quoted = new List<string>(); foreach (var argument in arguments) quoted.Add(Quote(argument));
            var info = new ProcessStartInfo(executable, string.Join(" ", quoted.ToArray())) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Root };
            info.EnvironmentVariables.Remove("PYTHONHOME"); info.EnvironmentVariables.Remove("PYTHONPATH");
            return info;
        }
        public static void Log(Exception error) {
            try { Directory.CreateDirectory(Data); File.AppendAllText(Path.Combine(Data, "desktop.log"), DateTime.UtcNow.ToString("s") + " " + error.ToString() + Environment.NewLine); } catch { }
        }
        public static void WriteAtomic(string target,string text) {
            Directory.CreateDirectory(Path.GetDirectoryName(target));
            var temporary=target+"."+Guid.NewGuid().ToString("N")+".tmp";
            try {
                using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)) {
                    var bytes=new UTF8Encoding(false).GetBytes(text); stream.Write(bytes,0,bytes.Length); stream.Flush(true);
                }
                if(File.Exists(target)) File.Replace(temporary,target,null); else File.Move(temporary,target);
            } finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
        [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
        public static void DarkFrame(Form form) { try { int enabled = 1; DwmSetWindowAttribute(form.Handle, 20, ref enabled, 4); } catch { } }
        public static Button Button(string label, EventHandler handler) {
            var button = new Button { Text = label, AutoSize = true, Height = 36, MinimumSize = new Size(42, 36), FlatStyle = FlatStyle.Flat, ForeColor = Color.FromArgb(237,240,247), BackColor = Color.FromArgb(29,36,47), Font = new Font("Segoe UI", 10), Margin = new Padding(5), Padding = new Padding(8,3,8,3), Cursor = Cursors.Hand };
            button.FlatAppearance.BorderColor = Color.FromArgb(47,58,75); button.Click += handler; return button;
        }
    }

    internal sealed class Preferences {
        public Dictionary<string,string> profiles = new Dictionary<string,string>();
        public Dictionary<string,ReaderPreferences> readers = new Dictionary<string,ReaderPreferences>();
        public string mode = "";
        public string server = "";
        public string serverName = "";
        public string theme = "coral";
        public string audio = "auto";
        public bool autoplay = true;
        public bool playerActionHints = false;
        public bool playerFocusContrast = false;
        public bool multipleProfiles = false;
        public long updateOfferAfter = 0;
        public string ProfileFor(string address) { string value; return multipleProfiles && profiles!=null && profiles.TryGetValue(address,out value)?value:"default"; }
        public void StoreReader(string address,string profile) {
            if(readers==null) readers=new Dictionary<string,ReaderPreferences>();
            readers[address+"\n"+profile]=new ReaderPreferences { audio=audio,theme=theme,autoplay=autoplay,hints=playerActionHints,contrast=playerFocusContrast };
        }
        public void ApplyReader(string address,string profile) {
            ReaderPreferences value;
            if(readers==null || !readers.TryGetValue(address+"\n"+profile,out value)) value=new ReaderPreferences();
            audio=value.audio; theme=value.theme; autoplay=value.autoplay; playerActionHints=value.hints; playerFocusContrast=value.contrast;
        }
        public static Preferences Load() {
            try { return Native.Json.Deserialize<Preferences>(File.ReadAllText(Path.Combine(Native.Data,"desktop.json"))) ?? new Preferences(); }
            catch { return new Preferences(); }
        }
        public void Save() {
            Directory.CreateDirectory(Native.Data);
            var target = Path.Combine(Native.Data,"desktop.json"); var temporary = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try { File.WriteAllText(temporary, Native.Json.Serialize(this), new UTF8Encoding(false)); if(File.Exists(target)) File.Replace(temporary,target,null); else File.Move(temporary,target); }
            finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
    }
    internal sealed class ReaderPreferences {
        public string audio="auto",theme="coral";
        public bool autoplay=true,hints=false,contrast=false;
    }
}
