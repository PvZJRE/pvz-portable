// PvZ-Portable single-file Windows launcher (compiled by the build with the .NET Framework's
// own csc.exe - no third-party tool, no bundled runtime). It embeds the game jar as a resource,
// keeps the libraries external beside it, finds the locally installed Java 25 and launches the
// loader's KnotClient. When Java is missing or older than 25 it shows one minimal prompt that
// opens the Adoptium download page.
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Drawing;
using System.Windows.Forms;

class PvZLauncher
{
    const int REQUIRED_MAJOR = 25;

    [STAThread]
    static int Main(string[] args)
    {
        string exeDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        string libsDir = Path.Combine(exeDir, "libraries");
        StreamWriter log = new StreamWriter(Path.Combine(exeDir, "launcher.log"), false, Encoding.UTF8);

        try
        {
            log.WriteLine("launcher start; exeDir=" + exeDir);
            // Java 25 只有 64 位（x64/arm64）构建：32 位环境直接进专用提示，不走 Java 查找
            if (!Environment.Is64BitOperatingSystem || !Environment.Is64BitProcess)
            {
                log.WriteLine("32-bit environment detected; showing the unsupported prompt");
                ShowUnsupported32BitPrompt();
                return 1;
            }
            bool forcePrompt = false;
            if (args != null)
            {
                foreach (string argument in args)
                {
                    if (argument == "--show-java-prompt")
                    {
                        forcePrompt = true;
                    }
                }
            }
            string javaExe = forcePrompt ? null : FindJava(log);
            log.WriteLine("javaExe=" + (javaExe ?? "<null>"));
            if (javaExe == null)
            {
                log.WriteLine("no java 25 found; showing the download prompt");
                if (ShowJavaDownloadPrompt())
                {
                    Process.Start("https://adoptium.net/temurin/releases/?version=" + REQUIRED_MAJOR);
                }
                return 1;
            }

            string jarPath = ExtractEmbeddedJar(exeDir, log);
            log.WriteLine("game jar=" + jarPath);

            // the game jar alone is not enough: the libraries the manifest names ride beside the
            // exe (the dist layout: libraries/) or two levels up (the Minecraft install layout:
            // the exe inside versions/<id>/, libraries/ at the .minecraft root)
            string[] libraryRoots =
            {
                Path.Combine(exeDir, "libraries"),
                Path.GetFullPath(Path.Combine(exeDir, "..", "..", "libraries")),
            };
            StringBuilder classpath = new StringBuilder(jarPath);
            int librariesFound = 0;
            foreach (string root in libraryRoots)
            {
                if (!Directory.Exists(root))
                {
                    log.WriteLine("library root missing: " + root);
                    continue;
                }
                foreach (string jar in Directory.EnumerateFiles(root, "*.jar", SearchOption.AllDirectories))
                {
                    classpath.Append(';').Append(jar);
                    librariesFound++;
                }
            }
            log.WriteLine("libraries on classpath: " + librariesFound);

        ProcessStartInfo launch = new ProcessStartInfo();
        launch.FileName = javaExe;
        launch.Arguments = "-Dpvz.gameDir=\"" + exeDir + "\" -cp \"" + classpath + "\" "
                + "net.fabricmc.loader.impl.launch.knot.KnotClient";
        if (args != null && args.Length > 0)
        {
            launch.Arguments += " " + string.Join(" ", args); // e.g. -debug
        }
            launch.WorkingDirectory = exeDir;
            launch.UseShellExecute = false;
            // the game's java.exe is a console-subsystem executable: without this flag it would
            // allocate its own console window next to the game window
            launch.CreateNoWindow = true;
            log.WriteLine("launching: " + javaExe + " " + launch.Arguments);

            using (Process game = Process.Start(launch))
            {
                game.WaitForExit();
                log.WriteLine("game exit=" + game.ExitCode);
                return game.ExitCode;
            }
        }
        catch (Exception e)
        {
            log.WriteLine("LAUNCHER EXCEPTION: " + e.GetType().Name + ": " + e.Message
                    + "\r\n" + e.StackTrace);
            MessageBox.Show(
                    "\u542F\u52A8\u5668\u9519\u8BEF\uFF1A" + e.Message,
                    "PlantsVsZombies",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            log.Close();
        }
    }

    /** The embedded game jar, materialized once in the per-user cache. */
    static string ExtractEmbeddedJar(string exeDir, StreamWriter log)
    {
        string cacheDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "PlantsVsZombies");
        Directory.CreateDirectory(cacheDir);

        Assembly assembly = Assembly.GetExecutingAssembly();
        string jarPath = Path.Combine(cacheDir, "PlantsVsZombies-embedded.jar");

        using (Stream source = assembly.GetManifestResourceStream("game.jar"))
        {
            if (source == null)
            {
                throw new FileNotFoundException("the embedded game jar is missing from the exe");
            }

            bool stale = !File.Exists(jarPath) || new FileInfo(jarPath).Length != source.Length;
            if (stale)
            {
                using (Stream target = File.Create(jarPath))
                {
                    source.CopyTo(target);
                }
            }
        }
        return jarPath;
    }

    /** 32 位系统的专用提示：Java 25 只有 64 位构建，本游戏无法在 32 位环境运行；
     * 单个"确 定"按钮，点击即关闭退出。 */
    static void ShowUnsupported32BitPrompt()
    {
        Form form = new Form();
        form.Text = "Plants vs. Zombies GOTY";
        try
        {
            form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch (Exception)
        {
            // an icon-less dialog is fine
        }
        form.FormBorderStyle = FormBorderStyle.FixedDialog;
        form.MaximizeBox = false;
        form.MinimizeBox = false;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.ClientSize = new Size(430, 96);

        Label message = new Label();
        message.Text = "\u4E0D\u652F\u6301 32 \u4F4D\u7CFB\u7EDF\uFF1A\u672C\u6E38\u620F\u9700\u8981 64 \u4F4D\u7684 Windows \u4E0E Java 25\u3002"
                + "\u8BF7\u5728 64 \u4F4D\u7CFB\u7EDF\u4E0A\u8FD0\u884C\u3002";
        message.Location = new Point(16, 16);
        message.Size = new Size(400, 40);

        Button confirm = new Button();
        confirm.Text = "\u786E \u5B9A";
        confirm.DialogResult = DialogResult.OK;
        confirm.Location = new Point((430 - 120) / 2, 62);
        confirm.Size = new Size(120, 26);

        form.Controls.Add(message);
        form.Controls.Add(confirm);
        form.AcceptButton = confirm;
        form.ShowDialog();
    }

    /** The minimal Java-download prompt: a dialog titled "Plants vs. Zombies GOTY" with the
     * game's own icon in its title bar; true when the player chose to open the download page. */
    static bool ShowJavaDownloadPrompt()
    {
        Form form = new Form();
        form.Text = "Plants vs. Zombies GOTY";
        try
        {
            form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch (Exception)
        {
            // an icon-less dialog is fine
        }
        form.FormBorderStyle = FormBorderStyle.FixedDialog;
        form.MaximizeBox = false;
        form.MinimizeBox = false;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.ClientSize = new Size(420, 96);

        Label message = new Label();
        message.Text = "\u672A\u627E\u5230 Java " + REQUIRED_MAJOR + "\uFF08\u9700\u8981 Java " + REQUIRED_MAJOR
                + " \u6216\u66F4\u9AD8\u7248\u672C\uFF09\u3002\r\n"
                + "\u662F\u5426\u6253\u5F00\u4E0B\u8F7D\u9875\u9762\uFF08Adoptium Temurin "
                + REQUIRED_MAJOR + "\uFF09\uFF1F";
        message.Location = new Point(16, 16);
        message.Size = new Size(390, 40);

        Button yes = new Button();
        yes.Text = "\u662F";
        yes.DialogResult = DialogResult.Yes;
        yes.Location = new Point(214, 62);
        yes.Size = new Size(90, 26);

        Button no = new Button();
        no.Text = "\u5426";
        no.DialogResult = DialogResult.No;
        no.Location = new Point(312, 62);
        no.Size = new Size(90, 26);

        form.Controls.Add(message);
        form.Controls.Add(yes);
        form.Controls.Add(no);
        form.AcceptButton = yes;
        form.CancelButton = no;

        return form.ShowDialog() == DialogResult.Yes;
    }

    /** The java.exe of the locally installed Java 25: JAVA_HOME, PATH, the registry (JavaSoft
     * JDK keys, what every OpenJDK installer writes) and the common OpenJDK vendor directories. */
    static string FindJava(StreamWriter log)
    {
        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome))
        {
            string candidate = Path.Combine(javaHome, "bin", "java.exe");
            log.WriteLine("JAVA_HOME candidate " + candidate + " exists=" + File.Exists(candidate));
            if (File.Exists(candidate) && IsRequiredVersion(candidate))
            {
                return candidate;
            }
        }

        foreach (string keyName in new string[]
        {
            @"SOFTWARE\JavaSoft\JDK",
            @"SOFTWARE\Eclipse Adoptium\JDK",
            @"SOFTWARE\Microsoft\JDK",
        })
        {
            foreach (string javaExe in RegistryJavaHomes(keyName))
            {
                if (IsRequiredVersion(javaExe))
                {
                    return javaExe;
                }
            }
        }

        foreach (string root in new string[]
        {
            @"C:\Program Files\Eclipse Adoptium",
            @"C:\Program Files\Microsoft",
            @"C:\Program Files\Java",
            @"C:\Program Files\Zulu",
        })
        {
            if (!Directory.Exists(root))
            {
                continue;
            }
            foreach (string home in Directory.GetDirectories(root, "jdk-*"))
            {
                string candidate = Path.Combine(home, "bin", "java.exe");
                if (File.Exists(candidate) && IsRequiredVersion(candidate))
                {
                    return candidate;
                }
            }
        }

        // the PATH java, last: it may be any vendor, so the version has to be verified
        string pathJava = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.System), "java.exe");
        string whereOut = RunCapture("where", "java.exe");
        if (!string.IsNullOrEmpty(whereOut))
        {
            foreach (string line in whereOut.Split('\r', '\n'))
            {
                string candidate = line.Trim();
                if (candidate.Length == 0 || !File.Exists(candidate))
                {
                    continue;
                }
                if (IsRequiredVersion(candidate))
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The java.exe candidates a registry key names, newest first (the keys' subkeys are
     * versions; their JavaHome value points at the installation). */
    static System.Collections.Generic.IEnumerable<string> RegistryJavaHomes(string keyName)
    {
        Microsoft.Win32.RegistryKey root = null;
        try
        {
            root = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(keyName);
            if (root == null)
            {
                yield break;
            }
            foreach (string version in root.GetSubKeyNames())
            {
                Microsoft.Win32.RegistryKey sub = null;
                try
                {
                    sub = root.OpenSubKey(version);
                    string home = sub == null ? null : (string)sub.GetValue("JavaHome");
                    if (string.IsNullOrEmpty(home) && sub != null)
                    {
                        Microsoft.Win32.RegistryKey deeper = root.OpenSubKey(version + "\\hotspot");
                        if (deeper != null)
                        {
                            home = (string)deeper.GetValue("JavaHome");
                            deeper.Close();
                        }
                    }
                    if (string.IsNullOrEmpty(home))
                    {
                        continue;
                    }
                    string candidate = Path.Combine(home, "bin", "java.exe");
                    if (File.Exists(candidate))
                    {
                        yield return candidate;
                    }
                }
                finally
                {
                    if (sub != null)
                    {
                        sub.Close();
                    }
                }
            }
        }
        finally
        {
            if (root != null)
            {
                root.Close();
            }
        }
    }

    /** true when the candidate java reports major version >= REQUIRED_MAJOR. */
    static bool IsRequiredVersion(string javaExe)
    {
        string version = RunCapture(javaExe, "-version");
        if (string.IsNullOrEmpty(version))
        {
            return false;
        }
        // java -version prints e.g.: openjdk version "25.0.3" 2025-04-15
        int quote = version.IndexOf('"');
        if (quote < 0)
        {
            return false;
        }
        int end = version.IndexOf('"', quote + 1);
        if (end < 0)
        {
            return false;
        }
        string number = version.Substring(quote + 1, end - quote - 1);
        int dot = number.IndexOf('.');
        string major = dot < 0 ? number : number.Substring(0, dot);
        int majorVersion;
        if (!int.TryParse(major, out majorVersion))
        {
            return false;
        }
        return majorVersion >= REQUIRED_MAJOR;
    }

    /** Runs a command and returns its combined output (java -version writes to stderr). */
    static string RunCapture(string fileName, string arguments)
    {
        try
        {
            ProcessStartInfo info = new ProcessStartInfo();
            info.FileName = fileName;
            info.Arguments = arguments;
            info.UseShellExecute = false;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.CreateNoWindow = true;
            using (Process process = Process.Start(info))
            {
                string output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
                process.WaitForExit();
                return output;
            }
        }
        catch (Exception)
        {
            return null;
        }
    }
}
