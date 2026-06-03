using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace CatchReportLauncher
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    internal sealed class MainForm : Form
    {
        private readonly string captureDir;
        private readonly string activeFile;
        private readonly TextBox logBox;
        private readonly ComboBox protocolBox;
        private readonly TextBox ipBox;
        private readonly NumericUpDown portBox;
        private string activeEtl;
        private string activePcapng;

        public MainForm()
        {
            Text = "Catch Report Windows";
            Width = 760;
            Height = 560;
            MinimumSize = new Size(680, 500);
            StartPosition = FormStartPosition.CenterScreen;

            captureDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                "CatchReport",
                "captures",
                "desktop"
            );
            activeFile = Path.Combine(captureDir, ".active-capture.txt");

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.Padding = new Padding(16);
            root.RowCount = 6;
            root.ColumnCount = 1;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(root);

            var title = new Label();
            title.Text = "Catch Report Windows 抓包";
            title.Font = new Font(Font.FontFamily, 18, FontStyle.Bold);
            title.AutoSize = true;
            root.Controls.Add(title, 0, 0);

            var subtitle = new Label();
            subtitle.Text = "管理员模式启动后，用 Windows pktmon 抓包，停止时自动转为 PCAPNG。";
            subtitle.AutoSize = true;
            subtitle.Margin = new Padding(0, 6, 0, 12);
            root.Controls.Add(subtitle, 0, 1);

            var filterPanel = new TableLayoutPanel();
            filterPanel.Dock = DockStyle.Top;
            filterPanel.ColumnCount = 6;
            filterPanel.RowCount = 1;
            filterPanel.AutoSize = true;
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            filterPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 100));
            root.Controls.Add(filterPanel, 0, 2);

            filterPanel.Controls.Add(new Label { Text = "协议", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
            protocolBox = new ComboBox();
            protocolBox.DropDownStyle = ComboBoxStyle.DropDownList;
            protocolBox.Items.AddRange(new object[] { "全部", "TCP", "UDP", "ICMP", "ICMPv6" });
            protocolBox.SelectedIndex = 0;
            filterPanel.Controls.Add(protocolBox, 1, 0);

            filterPanel.Controls.Add(new Label { Text = "IP", AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(12, 0, 4, 0) }, 2, 0);
            ipBox = new TextBox();
            ipBox.Dock = DockStyle.Fill;
            filterPanel.Controls.Add(ipBox, 3, 0);

            filterPanel.Controls.Add(new Label { Text = "端口", AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(12, 0, 4, 0) }, 4, 0);
            portBox = new NumericUpDown();
            portBox.Minimum = 0;
            portBox.Maximum = 65535;
            portBox.Dock = DockStyle.Fill;
            filterPanel.Controls.Add(portBox, 5, 0);

            var buttonPanel = new FlowLayoutPanel();
            buttonPanel.Dock = DockStyle.Fill;
            buttonPanel.AutoSize = true;
            buttonPanel.Margin = new Padding(0, 14, 0, 8);
            root.Controls.Add(buttonPanel, 0, 3);

            AddButton(buttonPanel, "开始抓包", StartCapture);
            AddButton(buttonPanel, "停止并转换", StopCapture);
            AddButton(buttonPanel, "打开抓包目录", OpenCaptureDir);
            AddButton(buttonPanel, "打开查看器", OpenViewer);
            AddButton(buttonPanel, "状态", ShowStatus);

            var pathLabel = new Label();
            pathLabel.Text = "输出目录：" + captureDir;
            pathLabel.AutoSize = true;
            pathLabel.Margin = new Padding(0, 0, 0, 8);
            root.Controls.Add(pathLabel, 0, 4);

            logBox = new TextBox();
            logBox.Dock = DockStyle.Fill;
            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Vertical;
            logBox.Font = new Font("Consolas", 10);
            root.Controls.Add(logBox, 0, 5);

            Directory.CreateDirectory(captureDir);
            LoadActiveCapture();
            WriteLine("准备就绪。抓包需要管理员权限；本 EXE 已声明管理员运行。");
        }

        private void AddButton(FlowLayoutPanel panel, string text, Action action)
        {
            var button = new Button();
            button.Text = text;
            button.AutoSize = true;
            button.Height = 34;
            button.Margin = new Padding(0, 0, 8, 8);
            button.Click += delegate { RunSafe(action); };
            panel.Controls.Add(button);
        }

        private void RunSafe(Action action)
        {
            try
            {
                action();
            }
            catch (Exception ex)
            {
                WriteLine("错误：" + ex.Message);
                MessageBox.Show(this, ex.Message, "Catch Report", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void StartCapture()
        {
            Directory.CreateDirectory(captureDir);
            RunPktmon("filter remove");

            string filter = BuildFilterArguments();
            if (filter.Length > 0)
            {
                RunPktmon("filter add catch-report " + filter);
            }

            string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
            activeEtl = Path.Combine(captureDir, "catch-report-" + stamp + ".etl");
            activePcapng = Path.Combine(captureDir, "catch-report-" + stamp + ".pcapng");
            SaveActiveCapture();

            RunPktmon("start --capture --comp nics --pkt-size 0 --file-name \"" + activeEtl + "\"");
            WriteLine("抓包已开始。ETL: " + activeEtl);
        }

        private void StopCapture()
        {
            Directory.CreateDirectory(captureDir);
            RunPktmon("stop");
            LoadActiveCapture();

            if (String.IsNullOrEmpty(activeEtl) || !File.Exists(activeEtl))
            {
                WriteLine("没有找到活动 ETL，已停止。");
                return;
            }

            RunPktmon("etl2pcap \"" + activeEtl + "\" --out \"" + activePcapng + "\"");
            if (File.Exists(activeFile))
            {
                File.Delete(activeFile);
            }
            WriteLine("已转换 PCAPNG: " + activePcapng);
        }

        private string BuildFilterArguments()
        {
            string args = "";
            string protocol = protocolBox.SelectedItem == null ? "" : protocolBox.SelectedItem.ToString();
            if (!String.IsNullOrEmpty(protocol) && protocol != "全部")
            {
                args += " -t " + protocol;
            }
            string ip = ipBox.Text.Trim();
            if (ip.Length > 0)
            {
                args += " -i " + ip;
            }
            if (portBox.Value > 0)
            {
                args += " -p " + ((int)portBox.Value).ToString();
            }
            return args.Trim();
        }

        private void OpenCaptureDir()
        {
            Directory.CreateDirectory(captureDir);
            Process.Start("explorer.exe", "\"" + captureDir + "\"");
        }

        private void OpenViewer()
        {
            string exeDir = AppDomain.CurrentDomain.BaseDirectory;
            string repoRoot = Directory.GetParent(exeDir) == null ? exeDir : Directory.GetParent(exeDir).FullName;
            string viewer = Path.Combine(repoRoot, "desktop", "pcap-viewer", "index.html");
            if (!File.Exists(viewer))
            {
                viewer = Path.Combine(exeDir, "desktop", "pcap-viewer", "index.html");
            }
            if (!File.Exists(viewer))
            {
                MessageBox.Show(this, "没有找到 desktop\\pcap-viewer\\index.html。可以从 GitHub 下载完整仓库后再打开查看器。", "Catch Report", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            Process.Start(new ProcessStartInfo(viewer) { UseShellExecute = true });
        }

        private void ShowStatus()
        {
            RunPktmon("status");
            RunPktmon("filter list");
        }

        private void RunPktmon(string arguments)
        {
            var info = new ProcessStartInfo("pktmon.exe", arguments);
            info.UseShellExecute = false;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.CreateNoWindow = true;
            using (var process = Process.Start(info))
            {
                string output = process.StandardOutput.ReadToEnd();
                string error = process.StandardError.ReadToEnd();
                process.WaitForExit();
                if (output.Length > 0) WriteLine(output.Trim());
                if (error.Length > 0) WriteLine(error.Trim());
                if (process.ExitCode != 0)
                {
                    throw new InvalidOperationException("pktmon 失败，退出码 " + process.ExitCode);
                }
            }
        }

        private void SaveActiveCapture()
        {
            File.WriteAllLines(activeFile, new string[] { activeEtl ?? "", activePcapng ?? "" });
        }

        private void LoadActiveCapture()
        {
            if (!File.Exists(activeFile)) return;
            string[] lines = File.ReadAllLines(activeFile);
            if (lines.Length > 0) activeEtl = lines[0];
            if (lines.Length > 1) activePcapng = lines[1];
        }

        private void WriteLine(string text)
        {
            logBox.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + text + Environment.NewLine);
        }
    }
}
