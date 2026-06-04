using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Text;
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
        private const int PageSize = 12;
        private readonly Color accent = Color.FromArgb(15, 118, 110);
        private readonly Color accentDark = Color.FromArgb(17, 94, 89);
        private readonly Color page = Color.FromArgb(246, 247, 249);
        private readonly Color panel = Color.White;
        private readonly Color border = Color.FromArgb(214, 220, 228);
        private readonly Color muted = Color.FromArgb(86, 98, 112);
        private readonly string captureDir;
        private readonly string activeFile;

        private Label statusLabel;
        private Label guideTitleLabel;
        private TextBox guideTextBox;
        private Button guidePreviousButton;
        private Button guideNextButton;
        private RadioButton directFlowRadio;
        private RadioButton proxyFlowRadio;
        private TextBox proxyHostBox;
        private NumericUpDown proxyPortBox;
        private Label proxyCheckLabel;
        private ComboBox protocolBox;
        private TextBox ipBox;
        private NumericUpDown portBox;
        private Button startButton;
        private Button stopButton;
        private ListBox captureList;
        private TextBox resultSearchBox;
        private Label resultSummaryLabel;
        private Label pageLabel;
        private FlowLayoutPanel packetPanel;
        private TextBox prettyBox;
        private TextBox rawBox;
        private TextBox logBox;
        private Timer liveTimer;

        private readonly List<CaptureFileItem> captureFiles = new List<CaptureFileItem>();
        private readonly List<PacketRecord> currentPackets = new List<PacketRecord>();
        private readonly List<PacketRecord> filteredPackets = new List<PacketRecord>();
        private string activeEtl;
        private string activePcapng;
        private WizardFlow wizardFlow = WizardFlow.None;
        private int wizardStep;
        private bool captureStartedThisSession;
        private bool captureStoppedThisSession;
        private bool proxyReadyThisSession;
        private bool suppressCaptureSelection;
        private int currentPage;

        public MainForm()
        {
            Text = "Catch Report Windows";
            Width = 1180;
            Height = 780;
            MinimumSize = new Size(980, 660);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);
            BackColor = page;

            captureDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                "CatchReport",
                "captures",
                "desktop"
            );
            activeFile = Path.Combine(captureDir, ".active-capture.txt");
            Directory.CreateDirectory(captureDir);

            BuildLayout();
            LoadActiveCapture();
            RefreshCaptureFiles(false);
            UpdateWizard();
            WriteLine("准备就绪。Windows 端已同步向导、搜索、分页、展开详情和文件清理。");
            WriteLine("管理员权限用于 pktmon；停止抓包后会自动把 ETL 转成 PCAPNG。");
        }

        private void BuildLayout()
        {
            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.BackColor = page;
            root.RowCount = 2;
            root.ColumnCount = 1;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(root);

            root.Controls.Add(BuildHeader(), 0, 0);

            var tabs = new TabControl();
            tabs.Dock = DockStyle.Fill;
            tabs.Margin = new Padding(16, 0, 16, 16);
            tabs.Controls.Add(BuildGuideTab());
            tabs.Controls.Add(BuildCaptureTab());
            tabs.Controls.Add(BuildResultsTab());
            tabs.Controls.Add(BuildHelpTab());
            root.Controls.Add(tabs, 0, 1);

            liveTimer = new Timer();
            liveTimer.Interval = 2500;
            liveTimer.Tick += delegate { RefreshCaptureFiles(true); };
        }

        private Control BuildHeader()
        {
            var header = new TableLayoutPanel();
            header.Dock = DockStyle.Top;
            header.AutoSize = true;
            header.ColumnCount = 2;
            header.RowCount = 1;
            header.Padding = new Padding(20, 18, 20, 14);
            header.BackColor = panel;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

            var titleBlock = new TableLayoutPanel();
            titleBlock.AutoSize = true;
            titleBlock.RowCount = 3;
            titleBlock.ColumnCount = 1;

            var title = new Label();
            title.Text = "Catch Report Windows";
            title.Font = new Font(Font.FontFamily, 20, FontStyle.Bold);
            title.ForeColor = Color.FromArgb(20, 30, 42);
            title.AutoSize = true;
            titleBlock.Controls.Add(title, 0, 0);

            var subtitle = new Label();
            subtitle.Text = "Windows pktmon 抓包 + 本机搜索预览，体验和安卓端保持同一套流程";
            subtitle.ForeColor = muted;
            subtitle.AutoSize = true;
            subtitle.Margin = new Padding(0, 6, 0, 0);
            titleBlock.Controls.Add(subtitle, 0, 1);

            statusLabel = new Label();
            statusLabel.Text = "未开始";
            statusLabel.ForeColor = accentDark;
            statusLabel.AutoSize = true;
            statusLabel.Margin = new Padding(0, 8, 0, 0);
            titleBlock.Controls.Add(statusLabel, 0, 2);

            header.Controls.Add(titleBlock, 0, 0);

            var actionRow = new FlowLayoutPanel();
            actionRow.AutoSize = true;
            actionRow.FlowDirection = FlowDirection.LeftToRight;
            actionRow.WrapContents = false;
            actionRow.Anchor = AnchorStyles.Right | AnchorStyles.Top;
            actionRow.Controls.Add(MakeButton("刷新结果", delegate { RefreshCaptureFiles(true); }, false));
            actionRow.Controls.Add(MakeButton("打开目录", OpenCaptureDir, false));
            header.Controls.Add(actionRow, 1, 0);

            return header;
        }

        private TabPage BuildGuideTab()
        {
            var tab = new TabPage("引导");
            tab.BackColor = page;

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.Padding = new Padding(18);
            root.RowCount = 4;
            root.ColumnCount = 1;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            tab.Controls.Add(root);

            var modeGroup = MakeGroup("选择流程");
            modeGroup.Height = 116;
            var modePanel = new FlowLayoutPanel();
            modePanel.Dock = DockStyle.Fill;
            modePanel.Padding = new Padding(14);
            modePanel.WrapContents = true;

            directFlowRadio = new RadioButton();
            directFlowRadio.Text = "不挂代理抓包";
            directFlowRadio.AutoSize = true;
            directFlowRadio.Margin = new Padding(0, 4, 24, 4);
            directFlowRadio.CheckedChanged += delegate
            {
                if (directFlowRadio.Checked) SelectWizardFlow(WizardFlow.Direct);
            };
            proxyFlowRadio = new RadioButton();
            proxyFlowRadio.Text = "挂代理抓包";
            proxyFlowRadio.AutoSize = true;
            proxyFlowRadio.Margin = new Padding(0, 4, 24, 4);
            proxyFlowRadio.CheckedChanged += delegate
            {
                if (proxyFlowRadio.Checked) SelectWizardFlow(WizardFlow.Proxy);
            };
            modePanel.Controls.Add(directFlowRadio);
            modePanel.Controls.Add(proxyFlowRadio);
            modePanel.Controls.Add(MakeInfoLabel("没有完成上一步时，下一步按钮会锁住。"));
            modeGroup.Controls.Add(modePanel);
            root.Controls.Add(modeGroup, 0, 0);

            guideTitleLabel = new Label();
            guideTitleLabel.Font = new Font(Font.FontFamily, 15, FontStyle.Bold);
            guideTitleLabel.ForeColor = Color.FromArgb(24, 34, 44);
            guideTitleLabel.AutoSize = true;
            guideTitleLabel.Margin = new Padding(0, 14, 0, 8);
            root.Controls.Add(guideTitleLabel, 0, 1);

            guideTextBox = MakeTextPanel(false);
            guideTextBox.Dock = DockStyle.Fill;
            root.Controls.Add(guideTextBox, 0, 2);

            var nav = new FlowLayoutPanel();
            nav.FlowDirection = FlowDirection.RightToLeft;
            nav.Dock = DockStyle.Fill;
            nav.AutoSize = true;
            guideNextButton = MakeButton("下一步", NextWizardStep, true);
            guidePreviousButton = MakeButton("上一步", PreviousWizardStep, false);
            nav.Controls.Add(guideNextButton);
            nav.Controls.Add(guidePreviousButton);
            root.Controls.Add(nav, 0, 3);

            return tab;
        }

        private TabPage BuildCaptureTab()
        {
            var tab = new TabPage("抓包");
            tab.BackColor = page;

            var split = new SplitContainer();
            split.Dock = DockStyle.Fill;
            split.Orientation = Orientation.Vertical;
            split.SplitterDistance = 520;
            split.Panel1.Padding = new Padding(18);
            split.Panel2.Padding = new Padding(18);
            tab.Controls.Add(split);

            var captureGroup = MakeGroup("抓包控制");
            captureGroup.Dock = DockStyle.Fill;
            var captureRoot = new TableLayoutPanel();
            captureRoot.Dock = DockStyle.Fill;
            captureRoot.Padding = new Padding(14);
            captureRoot.ColumnCount = 2;
            captureRoot.RowCount = 8;
            captureRoot.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            captureRoot.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            for (int i = 0; i < 7; i++) captureRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            captureRoot.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            captureGroup.Controls.Add(captureRoot);
            split.Panel1.Controls.Add(captureGroup);

            captureRoot.Controls.Add(MakeFieldLabel("协议"), 0, 0);
            protocolBox = new ComboBox();
            protocolBox.DropDownStyle = ComboBoxStyle.DropDownList;
            protocolBox.Items.AddRange(new object[] { "全部", "TCP", "UDP", "ICMP", "ICMPv6" });
            protocolBox.SelectedIndex = 0;
            protocolBox.Dock = DockStyle.Top;
            captureRoot.Controls.Add(protocolBox, 1, 0);

            captureRoot.Controls.Add(MakeFieldLabel("IP"), 0, 1);
            ipBox = new TextBox();
            ipBox.Dock = DockStyle.Top;
            ipBox.Margin = new Padding(0, 6, 0, 6);
            captureRoot.Controls.Add(ipBox, 1, 1);

            captureRoot.Controls.Add(MakeFieldLabel("端口"), 0, 2);
            portBox = new NumericUpDown();
            portBox.Minimum = 0;
            portBox.Maximum = 65535;
            portBox.Dock = DockStyle.Top;
            portBox.Margin = new Padding(0, 6, 0, 6);
            captureRoot.Controls.Add(portBox, 1, 2);

            startButton = MakeButton("开始抓包", StartCapture, true);
            stopButton = MakeButton("停止并转换", StopCapture, false);
            var buttonRow = new FlowLayoutPanel();
            buttonRow.Dock = DockStyle.Fill;
            buttonRow.AutoSize = true;
            buttonRow.Controls.Add(startButton);
            buttonRow.Controls.Add(stopButton);
            buttonRow.Controls.Add(MakeButton("pktmon 状态", ShowStatus, false));
            captureRoot.SetColumnSpan(buttonRow, 2);
            captureRoot.Controls.Add(buttonRow, 0, 3);

            var pathBox = MakeTextPanel(false);
            pathBox.Text = "输出目录：" + captureDir + Environment.NewLine +
                           "停止时自动转换为 .pcapng；结果页可以直接搜索、分页、展开包详情。";
            pathBox.Height = 86;
            captureRoot.SetColumnSpan(pathBox, 2);
            captureRoot.Controls.Add(pathBox, 0, 4);

            var liveRow = new FlowLayoutPanel();
            liveRow.Dock = DockStyle.Fill;
            liveRow.AutoSize = true;
            liveRow.Controls.Add(MakeButton("实时刷新结果：开/关", ToggleLiveRefresh, false));
            liveRow.Controls.Add(MakeInfoLabel("Windows pktmon 的实时文件是 ETL，通常停止转换后才能看到具体包字段。"));
            captureRoot.SetColumnSpan(liveRow, 2);
            captureRoot.Controls.Add(liveRow, 0, 5);

            logBox = MakeTextPanel(true);
            logBox.Dock = DockStyle.Fill;
            captureRoot.SetColumnSpan(logBox, 2);
            captureRoot.Controls.Add(logBox, 0, 7);

            var proxyGroup = MakeGroup("代理流程");
            proxyGroup.Dock = DockStyle.Fill;
            var proxyRoot = new TableLayoutPanel();
            proxyRoot.Dock = DockStyle.Fill;
            proxyRoot.Padding = new Padding(14);
            proxyRoot.ColumnCount = 2;
            proxyRoot.RowCount = 7;
            proxyRoot.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            proxyRoot.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            proxyGroup.Controls.Add(proxyRoot);
            split.Panel2.Controls.Add(proxyGroup);

            proxyRoot.Controls.Add(MakeFieldLabel("Clash 地址"), 0, 0);
            proxyHostBox = new TextBox();
            proxyHostBox.Text = "127.0.0.1";
            proxyHostBox.Dock = DockStyle.Top;
            proxyRoot.Controls.Add(proxyHostBox, 1, 0);

            proxyRoot.Controls.Add(MakeFieldLabel("端口"), 0, 1);
            proxyPortBox = new NumericUpDown();
            proxyPortBox.Minimum = 1;
            proxyPortBox.Maximum = 65535;
            proxyPortBox.Value = 7890;
            proxyPortBox.Dock = DockStyle.Top;
            proxyPortBox.Margin = new Padding(0, 6, 0, 6);
            proxyRoot.Controls.Add(proxyPortBox, 1, 1);

            var proxyButtons = new FlowLayoutPanel();
            proxyButtons.Dock = DockStyle.Fill;
            proxyButtons.AutoSize = true;
            proxyButtons.Controls.Add(MakeButton("检测代理", CheckProxy, true));
            proxyButtons.Controls.Add(MakeButton("使用 7890", delegate { proxyPortBox.Value = 7890; }, false));
            proxyButtons.Controls.Add(MakeButton("使用 7891", delegate { proxyPortBox.Value = 7891; }, false));
            proxyRoot.SetColumnSpan(proxyButtons, 2);
            proxyRoot.Controls.Add(proxyButtons, 0, 2);

            proxyCheckLabel = MakeInfoLabel("挂代理流程先确认 Clash/Mihomo 已运行，再开始抓包。");
            proxyRoot.SetColumnSpan(proxyCheckLabel, 2);
            proxyRoot.Controls.Add(proxyCheckLabel, 0, 3);

            var proxyHelp = MakeTextPanel(false);
            proxyHelp.Dock = DockStyle.Fill;
            proxyHelp.Text =
                "Windows 端推荐流程：" + Environment.NewLine +
                "1. Clash Party / Clash Verge 等客户端保持系统代理或 TUN 模式开启。" + Environment.NewLine +
                "2. 这里检测 127.0.0.1:7890 或 7891，确认本机代理入口存在。" + Environment.NewLine +
                "3. 点“开始抓包”，让目标软件产生流量。" + Environment.NewLine +
                "4. 点“停止并转换”，在结果页搜索域名、IP、端口、HTTP 字段、TLS SNI。" + Environment.NewLine + Environment.NewLine +
                "说明：如果目标软件只连本机 127.0.0.1 代理，pktmon 抓 NIC 主要看到代理出口到节点的流量；抓 VPN/TUN 内层要选相应虚拟网卡或后续接 Npcap。";
            proxyRoot.SetColumnSpan(proxyHelp, 2);
            proxyRoot.Controls.Add(proxyHelp, 0, 4);

            return tab;
        }

        private TabPage BuildResultsTab()
        {
            var tab = new TabPage("结果");
            tab.BackColor = page;

            var split = new SplitContainer();
            split.Dock = DockStyle.Fill;
            split.Orientation = Orientation.Vertical;
            split.SplitterDistance = 310;
            split.Panel1.Padding = new Padding(18);
            split.Panel2.Padding = new Padding(18);
            tab.Controls.Add(split);

            var fileGroup = MakeGroup("抓包文件");
            fileGroup.Dock = DockStyle.Fill;
            var fileRoot = new TableLayoutPanel();
            fileRoot.Dock = DockStyle.Fill;
            fileRoot.Padding = new Padding(12);
            fileRoot.RowCount = 4;
            fileRoot.ColumnCount = 1;
            fileRoot.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            fileRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            fileRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            fileRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            fileGroup.Controls.Add(fileRoot);
            split.Panel1.Controls.Add(fileGroup);

            captureList = new ListBox();
            captureList.Dock = DockStyle.Fill;
            captureList.SelectedIndexChanged += delegate
            {
                if (!suppressCaptureSelection) PreviewSelectedCapture();
            };
            fileRoot.Controls.Add(captureList, 0, 0);

            var fileButtons = new FlowLayoutPanel();
            fileButtons.AutoSize = true;
            fileButtons.Controls.Add(MakeButton("刷新", delegate { RefreshCaptureFiles(true); }, true));
            fileButtons.Controls.Add(MakeButton("打开目录", OpenCaptureDir, false));
            fileRoot.Controls.Add(fileButtons, 0, 1);

            var deleteButtons = new FlowLayoutPanel();
            deleteButtons.AutoSize = true;
            deleteButtons.Controls.Add(MakeButton("删除选中", DeleteSelectedCapture, false));
            deleteButtons.Controls.Add(MakeDangerButton("一键清空", ClearCaptures));
            fileRoot.Controls.Add(deleteButtons, 0, 2);

            var fileNote = MakeInfoLabel("支持 .pcap/.pcapng/.cap；.etl 需要先停止转换。");
            fileRoot.Controls.Add(fileNote, 0, 3);

            var rightRoot = new TableLayoutPanel();
            rightRoot.Dock = DockStyle.Fill;
            rightRoot.RowCount = 5;
            rightRoot.ColumnCount = 1;
            rightRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            rightRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            rightRoot.RowStyles.Add(new RowStyle(SizeType.Percent, 52));
            rightRoot.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            rightRoot.RowStyles.Add(new RowStyle(SizeType.Percent, 48));
            split.Panel2.Controls.Add(rightRoot);

            var searchRow = new TableLayoutPanel();
            searchRow.Dock = DockStyle.Fill;
            searchRow.ColumnCount = 3;
            searchRow.RowCount = 1;
            searchRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            searchRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            searchRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            resultSearchBox = new TextBox();
            resultSearchBox.Dock = DockStyle.Fill;
            resultSearchBox.Margin = new Padding(0, 0, 8, 8);
            resultSearchBox.TextChanged += delegate { ApplySearch(); };
            searchRow.Controls.Add(resultSearchBox, 0, 0);
            searchRow.Controls.Add(MakeButton("搜索 / 预览", PreviewSelectedCapture, true), 1, 0);
            searchRow.Controls.Add(MakeButton("清空搜索", delegate { resultSearchBox.Text = ""; }, false), 2, 0);
            rightRoot.Controls.Add(searchRow, 0, 0);

            resultSummaryLabel = MakeInfoLabel("选择一个抓包文件开始预览。");
            rightRoot.Controls.Add(resultSummaryLabel, 0, 1);

            packetPanel = new FlowLayoutPanel();
            packetPanel.Dock = DockStyle.Fill;
            packetPanel.AutoScroll = true;
            packetPanel.WrapContents = false;
            packetPanel.FlowDirection = FlowDirection.TopDown;
            packetPanel.BackColor = page;
            rightRoot.Controls.Add(packetPanel, 0, 2);

            var pageRow = new FlowLayoutPanel();
            pageRow.AutoSize = true;
            pageRow.FlowDirection = FlowDirection.RightToLeft;
            pageRow.Controls.Add(MakeButton("下一页", NextPage, false));
            pageRow.Controls.Add(MakeButton("上一页", PreviousPage, false));
            pageLabel = MakeInfoLabel("还没有分页结果");
            pageLabel.Margin = new Padding(0, 8, 16, 0);
            pageRow.Controls.Add(pageLabel);
            rightRoot.Controls.Add(pageRow, 0, 3);

            var detailTabs = new TabControl();
            detailTabs.Dock = DockStyle.Fill;
            var prettyTab = new TabPage("美化结果");
            prettyTab.BackColor = page;
            prettyBox = MakeTextPanel(false);
            prettyBox.Dock = DockStyle.Fill;
            prettyTab.Controls.Add(prettyBox);
            var rawTab = new TabPage("RAW 数据");
            rawTab.BackColor = page;
            rawBox = MakeTextPanel(true);
            rawBox.Dock = DockStyle.Fill;
            rawTab.Controls.Add(rawBox);
            detailTabs.Controls.Add(prettyTab);
            detailTabs.Controls.Add(rawTab);
            rightRoot.Controls.Add(detailTabs, 0, 4);

            return tab;
        }

        private TabPage BuildHelpTab()
        {
            var tab = new TabPage("说明");
            tab.BackColor = page;

            var help = MakeTextPanel(false);
            help.Dock = DockStyle.Fill;
            help.Margin = new Padding(18);
            help.Text =
                "Catch Report Windows 使用说明" + Environment.NewLine + Environment.NewLine +
                "1. 不挂代理抓包：选择流程 -> 开始抓包 -> 访问测试目标 -> 停止并转换 -> 在结果页搜索和展开详情。" + Environment.NewLine +
                "2. 挂代理抓包：先启动 Clash/Mihomo，检测 7890/7891 -> 开始抓包 -> 让目标软件走代理 -> 停止并转换 -> 查看结果。" + Environment.NewLine +
                "3. 结果页支持搜索协议、IP、端口、DNS 域名、HTTP 明文字段、TLS SNI/ALPN 和字段 value。" + Environment.NewLine +
                "4. 每个包在列表里直接展开，不需要输入抓包号；分页默认每页 12 条。" + Environment.NewLine +
                "5. RAW 数据会显示 HEX/ASCII；HTTPS 正文默认加密，没有 MITM 证书或密钥时只能看到 SNI/ALPN、IP、端口和 DNS 等可见字段。" + Environment.NewLine +
                "6. 删除选中会同时尝试删除同名 ETL/PCAPNG/JSON；一键清空只清理 Catch Report 的抓包目录。" + Environment.NewLine + Environment.NewLine +
                "当前 Windows 后端使用系统自带 pktmon。pktmon 写入的是 ETL，停止后转换成 PCAPNG 才能稳定解析包详情；后续接 Npcap 后可以补更细的网卡选择和 loopback 抓包。";
            tab.Controls.Add(help);
            return tab;
        }

        private void SelectWizardFlow(WizardFlow flow)
        {
            wizardFlow = flow;
            wizardStep = 0;
            captureStartedThisSession = false;
            captureStoppedThisSession = false;
            proxyReadyThisSession = flow == WizardFlow.Direct;
            if (flow == WizardFlow.Direct)
            {
                statusLabel.Text = "已选择：不挂代理抓包";
            }
            else
            {
                statusLabel.Text = "已选择：挂代理抓包，请先检测代理";
            }
            UpdateWizard();
        }

        private void PreviousWizardStep()
        {
            if (wizardStep > 0) wizardStep -= 1;
            UpdateWizard();
        }

        private void NextWizardStep()
        {
            if (!CanAdvanceWizard())
            {
                MessageBox.Show(this, "当前步骤还没有完成，先按提示完成上一步。", "Catch Report", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            if (wizardStep < GetSteps().Length - 1) wizardStep += 1;
            UpdateWizard();
        }

        private void UpdateWizard()
        {
            string[] steps = GetSteps();
            if (wizardFlow == WizardFlow.None)
            {
                guideTitleLabel.Text = "选择抓包流程";
                guideTextBox.Text = "先选择“不挂代理抓包”或“挂代理抓包”。Windows 端会按同一套流程引导你：配置 -> 开始 -> 停止转换 -> 搜索查看。";
                guidePreviousButton.Enabled = false;
                guideNextButton.Enabled = false;
                return;
            }

            if (wizardStep < 0) wizardStep = 0;
            if (wizardStep >= steps.Length) wizardStep = steps.Length - 1;
            guideTitleLabel.Text = "第 " + (wizardStep + 1).ToString(CultureInfo.InvariantCulture) + "/" + steps.Length.ToString(CultureInfo.InvariantCulture) + " 步";
            guideTextBox.Text = steps[wizardStep];
            guidePreviousButton.Enabled = wizardStep > 0;
            guideNextButton.Enabled = wizardStep < steps.Length - 1 && CanAdvanceWizard();
        }

        private string[] GetSteps()
        {
            if (wizardFlow == WizardFlow.Proxy)
            {
                return new string[]
                {
                    "确认代理入口。通常 Clash mixed 端口是 7890，SOCKS 端口是 7891。先到“抓包”页点“检测代理”，成功后才能下一步。",
                    "开始抓包。Catch Report Windows 会调用 pktmon，并保存 ETL 活动文件。目标软件继续按你的 Clash/系统代理方式访问网络。",
                    "产生需要测试的代理流量。比如打开目标软件、访问测试域名、执行登录/请求流程。Windows 实时预览会刷新文件列表，但详细字段通常要等停止转换。",
                    "停止并转换。点“停止并转换”后生成 PCAPNG，然后到“结果”页按域名、IP、端口、字段 value 搜索并展开单包。"
                };
            }
            if (wizardFlow == WizardFlow.Direct)
            {
                return new string[]
                {
                    "确认不挂代理抓包。这个流程直接抓 Windows 网卡流量，适合普通网络测试、DNS/TCP/UDP/HTTP 明文和 TLS SNI 观察。",
                    "开始抓包。可以先设置协议、IP、端口过滤；不填就是全量抓 NIC 组件。",
                    "产生测试流量后停止转换。pktmon 会把 ETL 转成 PCAPNG，结果页才能解析包详情。",
                    "查看结果。结果页可以搜索、分页、展开每个包，看美化字段和 RAW HEX/ASCII，也能删除单个文件或一键清空。"
                };
            }
            return new string[] { "先选择抓包流程。" };
        }

        private bool CanAdvanceWizard()
        {
            if (wizardFlow == WizardFlow.None) return false;
            if (wizardFlow == WizardFlow.Proxy && wizardStep == 0) return proxyReadyThisSession;
            if (wizardStep == 0) return true;
            if (wizardStep == 1) return captureStartedThisSession;
            if (wizardStep == 2) return captureStoppedThisSession || HasConvertedCapture();
            return true;
        }

        private bool HasConvertedCapture()
        {
            for (int i = 0; i < captureFiles.Count; i++)
            {
                if (captureFiles[i].CanPreview) return true;
            }
            return false;
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
            captureStartedThisSession = true;
            captureStoppedThisSession = false;
            statusLabel.Text = "抓包中：" + Path.GetFileName(activeEtl);
            startButton.Enabled = false;
            stopButton.Enabled = true;
            WriteLine("抓包已开始。ETL: " + activeEtl);
            UpdateWizard();
            RefreshCaptureFiles(false);
        }

        private void StopCapture()
        {
            Directory.CreateDirectory(captureDir);
            RunPktmon("stop");
            LoadActiveCapture();

            if (String.IsNullOrEmpty(activeEtl) || !File.Exists(activeEtl))
            {
                WriteLine("没有找到活动 ETL，已停止。");
                captureStoppedThisSession = true;
                startButton.Enabled = true;
                stopButton.Enabled = false;
                statusLabel.Text = "已停止，没有找到活动 ETL";
                UpdateWizard();
                return;
            }

            RunPktmon("etl2pcap \"" + activeEtl + "\" --out \"" + activePcapng + "\"");
            if (File.Exists(activeFile)) File.Delete(activeFile);
            captureStoppedThisSession = true;
            startButton.Enabled = true;
            stopButton.Enabled = false;
            statusLabel.Text = "已转换：" + Path.GetFileName(activePcapng);
            WriteLine("已转换 PCAPNG: " + activePcapng);
            RefreshCaptureFiles(true);
            SelectCaptureByPath(activePcapng);
            UpdateWizard();
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
                args += " -p " + ((int)portBox.Value).ToString(CultureInfo.InvariantCulture);
            }
            return args.Trim();
        }

        private void CheckProxy()
        {
            string host = proxyHostBox.Text.Trim();
            int port = (int)proxyPortBox.Value;
            try
            {
                using (var client = new TcpClient())
                {
                    IAsyncResult result = client.BeginConnect(host, port, null, null);
                    bool ok = result.AsyncWaitHandle.WaitOne(1600);
                    if (!ok)
                    {
                        throw new TimeoutException("连接超时");
                    }
                    client.EndConnect(result);
                }
                proxyReadyThisSession = true;
                proxyCheckLabel.Text = "代理可连接：" + host + ":" + port.ToString(CultureInfo.InvariantCulture);
                statusLabel.Text = "代理入口已确认";
                WriteLine("代理检测成功：" + host + ":" + port.ToString(CultureInfo.InvariantCulture));
            }
            catch (Exception ex)
            {
                proxyReadyThisSession = false;
                proxyCheckLabel.Text = "代理不可连接：" + ex.Message;
                statusLabel.Text = "代理检测失败";
                WriteLine("代理检测失败：" + ex.Message);
            }
            UpdateWizard();
        }

        private void RefreshCaptureFiles(bool autoPreview)
        {
            captureFiles.Clear();
            Directory.CreateDirectory(captureDir);
            string[] patterns = new string[] { "*.pcapng", "*.pcap", "*.cap", "*.etl" };
            for (int i = 0; i < patterns.Length; i++)
            {
                string[] files = Directory.GetFiles(captureDir, patterns[i]);
                for (int j = 0; j < files.Length; j++)
                {
                    captureFiles.Add(new CaptureFileItem(files[j]));
                }
            }
            captureFiles.Sort(delegate(CaptureFileItem a, CaptureFileItem b)
            {
                int cmp = b.LastWrite.CompareTo(a.LastWrite);
                if (cmp != 0) return cmp;
                return String.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase);
            });

            string selectedPath = null;
            if (captureList != null && captureList.SelectedItem is CaptureFileItem)
            {
                selectedPath = ((CaptureFileItem)captureList.SelectedItem).Path;
            }

            suppressCaptureSelection = true;
            captureList.Items.Clear();
            for (int i = 0; i < captureFiles.Count; i++)
            {
                captureList.Items.Add(captureFiles[i]);
            }

            if (captureFiles.Count == 0)
            {
                suppressCaptureSelection = false;
                currentPackets.Clear();
                filteredPackets.Clear();
                RenderPacketPage();
                ShowPreview("还没有抓包结果。" + Environment.NewLine + captureDir, "暂无 RAW 数据");
                return;
            }

            int selected = 0;
            if (selectedPath != null)
            {
                for (int i = 0; i < captureFiles.Count; i++)
                {
                    if (String.Equals(captureFiles[i].Path, selectedPath, StringComparison.OrdinalIgnoreCase))
                    {
                        selected = i;
                        break;
                    }
                }
            }
            captureList.SelectedIndex = selected;
            suppressCaptureSelection = false;
            if (autoPreview) PreviewSelectedCapture();
            UpdateWizard();
        }

        private void SelectCaptureByPath(string path)
        {
            for (int i = 0; i < captureList.Items.Count; i++)
            {
                var item = captureList.Items[i] as CaptureFileItem;
                if (item != null && String.Equals(item.Path, path, StringComparison.OrdinalIgnoreCase))
                {
                    captureList.SelectedIndex = i;
                    return;
                }
            }
        }

        private void PreviewSelectedCapture()
        {
            var item = captureList == null ? null : captureList.SelectedItem as CaptureFileItem;
            if (item == null)
            {
                ShowPreview("没有选择抓包文件。", "暂无 RAW 数据");
                return;
            }
            if (!item.CanPreview)
            {
                currentPackets.Clear();
                filteredPackets.Clear();
                RenderPacketPage();
                ShowPreview(
                    "文件：" + item.Name + Environment.NewLine +
                    "类型：ETL 活动/原始文件" + Environment.NewLine +
                    "说明：请在抓包页点击“停止并转换”，生成同名 .pcapng 后再查看字段。",
                    "ETL 不能在当前内置解析器里直接展示 RAW 包。"
                );
                return;
            }

            try
            {
                statusLabel.Text = "正在解析：" + item.Name;
                Cursor = Cursors.WaitCursor;
                ParseResult parsed = CaptureParser.Parse(item.Path);
                currentPackets.Clear();
                currentPackets.AddRange(parsed.Packets);
                ApplySearch();
                ShowPreview(
                    "文件：" + item.Name + Environment.NewLine +
                    "格式：" + parsed.Format + " / " + CaptureParser.LinkTypeName(parsed.LinkType) + Environment.NewLine +
                    "扫描包数：" + parsed.Packets.Count.ToString(CultureInfo.InvariantCulture) + Environment.NewLine +
                    "搜索结果会在上方分页列表展示，点击每个包即可展开字段 / Value。",
                    "选择某个包后，这里显示 HEX/ASCII 原始数据。"
                );
                statusLabel.Text = "已解析：" + parsed.Packets.Count.ToString(CultureInfo.InvariantCulture) + " 个包";
            }
            catch (Exception ex)
            {
                currentPackets.Clear();
                filteredPackets.Clear();
                RenderPacketPage();
                ShowPreview("预览失败：" + ex.Message, ex.ToString());
                statusLabel.Text = "预览失败";
            }
            finally
            {
                Cursor = Cursors.Default;
            }
        }

        private void ApplySearch()
        {
            filteredPackets.Clear();
            string query = resultSearchBox == null ? "" : resultSearchBox.Text.Trim().ToLowerInvariant();
            for (int i = 0; i < currentPackets.Count; i++)
            {
                PacketRecord packet = currentPackets[i];
                if (query.Length == 0 || packet.SearchText().ToLowerInvariant().Contains(query))
                {
                    filteredPackets.Add(packet);
                }
            }
            currentPage = 0;
            resultSummaryLabel.Text = "总包数 " + currentPackets.Count.ToString(CultureInfo.InvariantCulture) +
                                      "，匹配 " + filteredPackets.Count.ToString(CultureInfo.InvariantCulture) +
                                      (query.Length == 0 ? "" : "，搜索：" + query);
            RenderPacketPage();
        }

        private void PreviousPage()
        {
            if (currentPage > 0)
            {
                currentPage -= 1;
                RenderPacketPage();
            }
        }

        private void NextPage()
        {
            int maxPage = filteredPackets.Count == 0 ? 0 : (filteredPackets.Count - 1) / PageSize;
            if (currentPage < maxPage)
            {
                currentPage += 1;
                RenderPacketPage();
            }
        }

        private void RenderPacketPage()
        {
            if (packetPanel == null) return;
            packetPanel.SuspendLayout();
            packetPanel.Controls.Clear();
            if (filteredPackets.Count == 0)
            {
                packetPanel.Controls.Add(MakeEmptyCard("没有匹配的数据包。"));
                pageLabel.Text = "0 / 0";
                packetPanel.ResumeLayout();
                return;
            }

            int maxPage = (filteredPackets.Count - 1) / PageSize;
            if (currentPage > maxPage) currentPage = maxPage;
            int start = currentPage * PageSize;
            int end = Math.Min(start + PageSize, filteredPackets.Count);
            for (int i = start; i < end; i++)
            {
                packetPanel.Controls.Add(MakePacketCard(filteredPackets[i]));
            }
            pageLabel.Text = "第 " + (currentPage + 1).ToString(CultureInfo.InvariantCulture) + " / " + (maxPage + 1).ToString(CultureInfo.InvariantCulture) + " 页";
            packetPanel.ResumeLayout();
        }

        private Control MakePacketCard(PacketRecord packet)
        {
            var card = new Panel();
            card.Width = Math.Max(640, packetPanel.ClientSize.Width - 28);
            card.AutoSize = true;
            card.BackColor = panel;
            card.Padding = new Padding(12);
            card.Margin = new Padding(0, 0, 0, 10);
            card.BorderStyle = BorderStyle.FixedSingle;

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Top;
            root.AutoSize = true;
            root.ColumnCount = 2;
            root.RowCount = 2;
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            root.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            card.Controls.Add(root);

            var summary = new Label();
            summary.AutoSize = true;
            summary.MaximumSize = new Size(card.Width - 150, 0);
            summary.Font = new Font(Font.FontFamily, 9, FontStyle.Bold);
            summary.ForeColor = Color.FromArgb(25, 32, 41);
            summary.Text = "#" + packet.Index.ToString(CultureInfo.InvariantCulture) + "  " +
                           packet.Decoded.Protocol + "  " +
                           packet.Decoded.Source + " -> " +
                           packet.Decoded.Destination + "  " +
                           packet.Length.ToString(CultureInfo.InvariantCulture) + " B";
            root.Controls.Add(summary, 0, 0);

            var expand = MakeButton("展开详情", null, false);
            root.Controls.Add(expand, 1, 0);

            var info = new Label();
            info.AutoSize = true;
            info.MaximumSize = new Size(card.Width - 40, 0);
            info.ForeColor = muted;
            info.Margin = new Padding(0, 8, 0, 0);
            info.Text = packet.TimeText() + "    " + packet.Decoded.Info;
            root.SetColumnSpan(info, 2);
            root.Controls.Add(info, 0, 1);

            TextBox detail = MakeTextPanel(true);
            detail.Dock = DockStyle.Top;
            detail.Height = 250;
            detail.Visible = false;
            detail.Margin = new Padding(0, 10, 0, 0);
            card.Controls.Add(detail);
            detail.BringToFront();

            EventHandler toggle = delegate
            {
                detail.Visible = !detail.Visible;
                if (detail.Visible)
                {
                    string pretty = packet.PrettyDetail();
                    string raw = CaptureParser.HexDump(packet.Bytes, 1024);
                    detail.Text = pretty + Environment.NewLine + Environment.NewLine + "HEX / ASCII" + Environment.NewLine + raw;
                    ShowPreview(pretty, raw);
                    expand.Text = "收起详情";
                }
                else
                {
                    expand.Text = "展开详情";
                }
            };
            expand.Click += toggle;
            summary.Click += toggle;
            info.Click += toggle;
            return card;
        }

        private Control MakeEmptyCard(string text)
        {
            var box = MakeTextPanel(false);
            box.Text = text;
            box.Width = Math.Max(620, packetPanel.ClientSize.Width - 28);
            box.Height = 90;
            return box;
        }

        private void DeleteSelectedCapture()
        {
            var item = captureList == null ? null : captureList.SelectedItem as CaptureFileItem;
            if (item == null) return;
            DialogResult result = MessageBox.Show(this, "确定删除 " + item.Name + " 以及同名相关文件吗？", "删除抓包文件", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
            if (result != DialogResult.OK) return;
            int deleted = DeleteCaptureSet(item.Path);
            WriteLine("已删除 " + deleted.ToString(CultureInfo.InvariantCulture) + " 个文件：" + item.Name);
            RefreshCaptureFiles(false);
        }

        private void ClearCaptures()
        {
            if (captureFiles.Count == 0) return;
            DialogResult result = MessageBox.Show(this, "确定清空 Catch Report 的 Windows 抓包目录吗？这个操作不可撤销。", "一键清空", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
            if (result != DialogResult.OK) return;
            int deleted = 0;
            for (int i = 0; i < captureFiles.Count; i++)
            {
                deleted += DeleteCaptureSet(captureFiles[i].Path);
            }
            WriteLine("已清空抓包文件，删除 " + deleted.ToString(CultureInfo.InvariantCulture) + " 个文件。");
            RefreshCaptureFiles(false);
        }

        private int DeleteCaptureSet(string path)
        {
            string full = Path.GetFullPath(path);
            string root = Path.GetFullPath(captureDir);
            if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase)) return 0;
            int deleted = 0;
            string dir = Path.GetDirectoryName(full);
            string name = Path.GetFileNameWithoutExtension(full);
            string[] candidates = new string[]
            {
                full,
                Path.Combine(dir, name + ".pcapng"),
                Path.Combine(dir, name + ".pcap"),
                Path.Combine(dir, name + ".cap"),
                Path.Combine(dir, name + ".etl"),
                Path.Combine(dir, Path.GetFileName(full) + ".json")
            };
            for (int i = 0; i < candidates.Length; i++)
            {
                try
                {
                    if (File.Exists(candidates[i]))
                    {
                        File.Delete(candidates[i]);
                        deleted += 1;
                    }
                }
                catch (Exception ex)
                {
                    WriteLine("删除失败：" + candidates[i] + "，" + ex.Message);
                }
            }
            return deleted;
        }

        private void ToggleLiveRefresh()
        {
            liveTimer.Enabled = !liveTimer.Enabled;
            statusLabel.Text = liveTimer.Enabled ? "实时刷新结果：开" : "实时刷新结果：关";
        }

        private void OpenCaptureDir()
        {
            Directory.CreateDirectory(captureDir);
            Process.Start("explorer.exe", "\"" + captureDir + "\"");
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
            info.StandardOutputEncoding = Encoding.UTF8;
            info.StandardErrorEncoding = Encoding.UTF8;
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
                    throw new InvalidOperationException("pktmon 失败，退出码 " + process.ExitCode.ToString(CultureInfo.InvariantCulture));
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

        private void ShowPreview(string pretty, string raw)
        {
            if (prettyBox != null) prettyBox.Text = pretty;
            if (rawBox != null) rawBox.Text = raw;
        }

        private void WriteLine(string text)
        {
            if (logBox == null) return;
            logBox.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + text + Environment.NewLine);
        }

        private GroupBox MakeGroup(string title)
        {
            var group = new GroupBox();
            group.Text = title;
            group.Font = new Font(Font.FontFamily, 10, FontStyle.Bold);
            group.ForeColor = Color.FromArgb(25, 32, 41);
            group.BackColor = panel;
            group.Padding = new Padding(8);
            return group;
        }

        private Label MakeFieldLabel(string text)
        {
            var label = new Label();
            label.Text = text;
            label.AutoSize = true;
            label.Anchor = AnchorStyles.Left;
            label.Margin = new Padding(0, 8, 12, 8);
            label.ForeColor = Color.FromArgb(25, 32, 41);
            return label;
        }

        private Label MakeInfoLabel(string text)
        {
            var label = new Label();
            label.Text = text;
            label.AutoSize = true;
            label.ForeColor = muted;
            label.Margin = new Padding(0, 8, 0, 8);
            return label;
        }

        private Button MakeButton(string text, Action action, bool primary)
        {
            var button = new Button();
            button.Text = text;
            button.AutoSize = true;
            button.Height = 34;
            button.Margin = new Padding(0, 0, 8, 8);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderSize = 1;
            button.FlatAppearance.BorderColor = primary ? accent : border;
            button.BackColor = primary ? accent : Color.White;
            button.ForeColor = primary ? Color.White : accentDark;
            if (action != null)
            {
                button.Click += delegate { RunSafe(action); };
            }
            return button;
        }

        private Button MakeDangerButton(string text, Action action)
        {
            var button = MakeButton(text, action, false);
            button.ForeColor = Color.FromArgb(180, 35, 24);
            button.FlatAppearance.BorderColor = Color.FromArgb(220, 160, 150);
            return button;
        }

        private TextBox MakeTextPanel(bool monospace)
        {
            var box = new TextBox();
            box.Multiline = true;
            box.ReadOnly = true;
            box.ScrollBars = ScrollBars.Vertical;
            box.BorderStyle = BorderStyle.FixedSingle;
            box.BackColor = monospace ? Color.FromArgb(250, 251, 253) : Color.FromArgb(248, 251, 250);
            box.ForeColor = Color.FromArgb(24, 32, 40);
            box.Font = monospace ? new Font("Consolas", 9F) : new Font("Microsoft YaHei UI", 9F);
            return box;
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
    }

    internal enum WizardFlow
    {
        None,
        Direct,
        Proxy
    }

    internal sealed class CaptureFileItem
    {
        public readonly string Path;
        public readonly string Name;
        public readonly long Length;
        public readonly DateTime LastWrite;
        public readonly bool CanPreview;

        public CaptureFileItem(string path)
        {
            Path = path;
            Name = System.IO.Path.GetFileName(path);
            var info = new FileInfo(path);
            Length = info.Exists ? info.Length : 0;
            LastWrite = info.Exists ? info.LastWriteTime : DateTime.MinValue;
            string ext = System.IO.Path.GetExtension(path).ToLowerInvariant();
            CanPreview = ext == ".pcap" || ext == ".pcapng" || ext == ".cap";
        }

        public override string ToString()
        {
            return Name + "  " + FormatBytes(Length);
        }

        private static string FormatBytes(long bytes)
        {
            if (bytes < 1024) return bytes.ToString(CultureInfo.InvariantCulture) + " B";
            double kib = bytes / 1024.0;
            if (kib < 1024) return kib.ToString("0.0", CultureInfo.InvariantCulture) + " KiB";
            return (kib / 1024.0).ToString("0.0", CultureInfo.InvariantCulture) + " MiB";
        }
    }

    internal sealed class ParseResult
    {
        public string Format;
        public int LinkType;
        public readonly List<PacketRecord> Packets = new List<PacketRecord>();
    }

    internal sealed class PacketRecord
    {
        public int Index;
        public double Timestamp;
        public int Length;
        public int CapturedLength;
        public int LinkType;
        public byte[] Bytes;
        public PacketDecoded Decoded;
        private string searchText;
        private List<FieldValue> fields;

        public string TimeText()
        {
            if (Timestamp <= 0) return "-";
            DateTime epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
            try
            {
                return epoch.AddSeconds(Timestamp).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture);
            }
            catch
            {
                return "-";
            }
        }

        public string SearchText()
        {
            if (searchText != null) return searchText;
            var builder = new StringBuilder();
            builder.Append(Decoded.Protocol).Append(' ')
                .Append(Decoded.Source).Append(' ')
                .Append(Decoded.Destination).Append(' ')
                .Append(Decoded.Info).Append(' ')
                .Append(Length.ToString(CultureInfo.InvariantCulture));
            List<FieldValue> list = Fields();
            for (int i = 0; i < list.Count; i++)
            {
                builder.Append(' ').Append(list[i].Name).Append(' ').Append(list[i].Value);
            }
            searchText = builder.ToString();
            return searchText;
        }

        public List<FieldValue> Fields()
        {
            if (fields == null) fields = CaptureParser.DecodePacketFields(Bytes, LinkType);
            return fields;
        }

        public string PrettyDetail()
        {
            var lines = new StringBuilder();
            lines.AppendLine("#" + Index.ToString(CultureInfo.InvariantCulture));
            lines.AppendLine("time: " + TimeText());
            lines.AppendLine("protocol: " + Decoded.Protocol);
            lines.AppendLine("source: " + Decoded.Source);
            lines.AppendLine("destination: " + Decoded.Destination);
            lines.AppendLine("length: " + Length.ToString(CultureInfo.InvariantCulture));
            lines.AppendLine("summary: " + Decoded.Info);
            lines.AppendLine();
            lines.AppendLine("字段 / Value");
            List<FieldValue> list = Fields();
            if (list.Count == 0)
            {
                lines.AppendLine("no decoded fields");
            }
            for (int i = 0; i < list.Count; i++)
            {
                lines.AppendLine(list[i].Name + ": " + list[i].Value);
            }
            return lines.ToString();
        }
    }

    internal sealed class PacketDecoded
    {
        public string Protocol;
        public string Source;
        public string Destination;
        public string Info;

        public PacketDecoded(string protocol, string source, string destination, string info)
        {
            Protocol = protocol;
            Source = source;
            Destination = destination;
            Info = info;
        }
    }

    internal sealed class FieldValue
    {
        public readonly string Name;
        public readonly string Value;

        public FieldValue(string name, string value)
        {
            Name = name;
            Value = value;
        }
    }

    internal static class CaptureParser
    {
        public static ParseResult Parse(string path)
        {
            byte[] bytes = File.ReadAllBytes(path);
            if (bytes.Length < 4) throw new InvalidOperationException("文件太小，不像 PCAP/PCAPNG。");
            uint firstLe = ReadU32Endian(bytes, 0, true);
            uint firstBe = ReadU32Endian(bytes, 0, false);
            if (firstLe == 0xa1b2c3d4 || firstLe == 0xa1b23c4d || firstBe == 0xa1b2c3d4 || firstBe == 0xa1b23c4d)
            {
                return ParsePcap(bytes);
            }
            if (firstLe == 0x0a0d0d0a || firstBe == 0x0a0d0d0a)
            {
                return ParsePcapng(bytes);
            }
            throw new InvalidOperationException("未识别的抓包格式，只支持 PCAP 和基础 PCAPNG。");
        }

        private static ParseResult ParsePcap(byte[] bytes)
        {
            uint magicLe = ReadU32Endian(bytes, 0, true);
            uint magicBe = ReadU32Endian(bytes, 0, false);
            bool little = magicLe == 0xa1b2c3d4 || magicLe == 0xa1b23c4d;
            bool nanos = magicLe == 0xa1b23c4d || magicBe == 0xa1b23c4d;
            int linkType = (int)ReadU32Endian(bytes, 20, little);
            var result = new ParseResult();
            result.Format = "PCAP";
            result.LinkType = linkType;
            int offset = 24;
            int index = 1;
            while (offset + 16 <= bytes.Length)
            {
                uint tsSec = ReadU32Endian(bytes, offset, little);
                uint tsFrac = ReadU32Endian(bytes, offset + 4, little);
                uint inclLen = ReadU32Endian(bytes, offset + 8, little);
                uint origLen = ReadU32Endian(bytes, offset + 12, little);
                offset += 16;
                if (inclLen > bytes.Length - offset) break;
                byte[] packetBytes = Slice(bytes, offset, (int)inclLen);
                result.Packets.Add(NewPacket(index, tsSec + tsFrac / (nanos ? 1000000000.0 : 1000000.0), (int)origLen, (int)inclLen, linkType, packetBytes));
                index += 1;
                offset += (int)inclLen;
            }
            return result;
        }

        private static ParseResult ParsePcapng(byte[] bytes)
        {
            var result = new ParseResult();
            result.Format = "PCAPNG";
            result.LinkType = 1;
            var interfaces = new List<int>();
            int offset = 0;
            bool little = true;
            int index = 1;

            while (offset + 12 <= bytes.Length)
            {
                uint blockType = ReadU32Endian(bytes, offset, true);
                if (blockType == 0x0a0d0d0a)
                {
                    uint magicLe = ReadU32Endian(bytes, offset + 8, true);
                    little = magicLe == 0x1a2b3c4d;
                }
                uint blockLength = ReadU32Endian(bytes, offset + 4, little);
                if (blockLength < 12 || offset + blockLength > bytes.Length) break;

                if (blockType == 0x00000001 && blockLength >= 20)
                {
                    int linkType = ReadU16Endian(bytes, offset + 8, little);
                    interfaces.Add(linkType);
                    result.LinkType = linkType;
                }
                else if (blockType == 0x00000006 && blockLength >= 32)
                {
                    int ifaceId = (int)ReadU32Endian(bytes, offset + 8, little);
                    uint tsHigh = ReadU32Endian(bytes, offset + 12, little);
                    uint tsLow = ReadU32Endian(bytes, offset + 16, little);
                    uint capturedLength = ReadU32Endian(bytes, offset + 20, little);
                    uint originalLength = ReadU32Endian(bytes, offset + 24, little);
                    int packetStart = offset + 28;
                    int packetEnd = packetStart + (int)capturedLength;
                    if (packetEnd <= offset + blockLength - 4)
                    {
                        int linkType = result.LinkType;
                        if (ifaceId >= 0 && ifaceId < interfaces.Count) linkType = interfaces[ifaceId];
                        double timestamp = (tsHigh * 4294967296.0 + tsLow) / 1000000.0;
                        byte[] packetBytes = Slice(bytes, packetStart, (int)capturedLength);
                        result.Packets.Add(NewPacket(index, timestamp, (int)originalLength, (int)capturedLength, linkType, packetBytes));
                        index += 1;
                    }
                }
                offset += (int)blockLength;
            }
            return result;
        }

        private static PacketRecord NewPacket(int index, double timestamp, int length, int capturedLength, int linkType, byte[] bytes)
        {
            var packet = new PacketRecord();
            packet.Index = index;
            packet.Timestamp = timestamp;
            packet.Length = length;
            packet.CapturedLength = capturedLength;
            packet.LinkType = linkType;
            packet.Bytes = bytes;
            packet.Decoded = DecodePacket(bytes, linkType);
            return packet;
        }

        public static PacketDecoded DecodePacket(byte[] bytes, int linkType)
        {
            if (linkType == 1) return DecodeEthernet(bytes);
            if (linkType == 101 || linkType == 228 || linkType == 229) return DecodeIp(bytes, 0);
            return new PacketDecoded("LINK-" + linkType.ToString(CultureInfo.InvariantCulture), "-", "-", "未解析链路类型");
        }

        private static PacketDecoded DecodeEthernet(byte[] bytes)
        {
            if (bytes.Length < 14) return Unknown("Ethernet frame too short");
            int typeOffset = 12;
            int etherType = ReadU16(bytes, typeOffset);
            if (etherType == 0x8100 && bytes.Length >= 18)
            {
                typeOffset = 16;
                etherType = ReadU16(bytes, typeOffset);
            }
            if (etherType == 0x0800 || etherType == 0x86dd) return DecodeIp(bytes, typeOffset + 2);
            if (etherType == 0x0806) return new PacketDecoded("ARP", Mac(Slice(bytes, 6, 6)), Mac(Slice(bytes, 0, 6)), "ARP");
            return new PacketDecoded("ETH 0x" + etherType.ToString("x4", CultureInfo.InvariantCulture), Mac(Slice(bytes, 6, 6)), Mac(Slice(bytes, 0, 6)), "未解析以太网类型");
        }

        private static PacketDecoded DecodeIp(byte[] bytes, int offset)
        {
            if (bytes.Length <= offset) return Unknown("IP packet too short");
            int version = bytes[offset] >> 4;
            if (version == 4) return DecodeIpv4(bytes, offset);
            if (version == 6) return DecodeIpv6(bytes, offset);
            return Unknown("Unknown IP version " + version.ToString(CultureInfo.InvariantCulture));
        }

        private static PacketDecoded DecodeIpv4(byte[] bytes, int offset)
        {
            if (bytes.Length < offset + 20) return Unknown("IPv4 packet too short");
            int ihl = (bytes[offset] & 0x0f) * 4;
            int protocol = bytes[offset + 9];
            string src = Ipv4(bytes, offset + 12);
            string dst = Ipv4(bytes, offset + 16);
            return DecodeTransport(bytes, offset + ihl, protocol, src, dst, "IPv4");
        }

        private static PacketDecoded DecodeIpv6(byte[] bytes, int offset)
        {
            if (bytes.Length < offset + 40) return Unknown("IPv6 packet too short");
            int protocol = bytes[offset + 6];
            string src = Ipv6(bytes, offset + 8);
            string dst = Ipv6(bytes, offset + 24);
            return DecodeTransport(bytes, offset + 40, protocol, src, dst, "IPv6");
        }

        private static PacketDecoded DecodeTransport(byte[] bytes, int offset, int protocol, string src, string dst, string ipVersion)
        {
            if (protocol == 6 && bytes.Length >= offset + 20)
            {
                int sport = ReadU16(bytes, offset);
                int dport = ReadU16(bytes, offset + 2);
                int tcpHeaderLength = (bytes[offset + 12] >> 4) * 4;
                byte[] payload = offset + tcpHeaderLength <= bytes.Length ? Slice(bytes, offset + tcpHeaderLength, bytes.Length - offset - tcpHeaderLength) : new byte[0];
                string app = ApplicationSummary(payload);
                return new PacketDecoded("TCP", src + ":" + sport.ToString(CultureInfo.InvariantCulture), dst + ":" + dport.ToString(CultureInfo.InvariantCulture), app.Length > 0 ? app : ipVersion + " TCP " + TcpFlags(bytes[offset + 13]));
            }
            if (protocol == 17 && bytes.Length >= offset + 8)
            {
                int sport = ReadU16(bytes, offset);
                int dport = ReadU16(bytes, offset + 2);
                string dns = sport == 53 || dport == 53 ? ParseDnsSummary(bytes, offset + 8) : "";
                return new PacketDecoded("UDP", src + ":" + sport.ToString(CultureInfo.InvariantCulture), dst + ":" + dport.ToString(CultureInfo.InvariantCulture), dns.Length > 0 ? dns : ipVersion + " UDP");
            }
            if (protocol == 1 || protocol == 58)
            {
                return new PacketDecoded(protocol == 1 ? "ICMP" : "ICMPv6", src, dst, ipVersion);
            }
            return new PacketDecoded(ipVersion + "/" + protocol.ToString(CultureInfo.InvariantCulture), src, dst, "未解析传输层协议");
        }

        public static List<FieldValue> DecodePacketFields(byte[] bytes, int linkType)
        {
            var lines = new List<FieldValue>();
            int ipOffset = 0;
            if (linkType == 1)
            {
                if (bytes.Length < 14)
                {
                    AddField(lines, "ethernet.error", "frame too short");
                    return lines;
                }
                int typeOffset = 12;
                int etherType = ReadU16(bytes, typeOffset);
                if (etherType == 0x8100 && bytes.Length >= 18)
                {
                    AddField(lines, "eth.vlan", (ReadU16(bytes, 14) & 0x0fff).ToString(CultureInfo.InvariantCulture));
                    typeOffset = 16;
                    etherType = ReadU16(bytes, typeOffset);
                }
                AddField(lines, "eth.source", Mac(Slice(bytes, 6, 6)));
                AddField(lines, "eth.destination", Mac(Slice(bytes, 0, 6)));
                AddField(lines, "eth.type", "0x" + etherType.ToString("x4", CultureInfo.InvariantCulture));
                ipOffset = typeOffset + 2;
            }
            if (linkType != 1 && linkType != 101 && linkType != 228 && linkType != 229)
            {
                AddField(lines, "link.type", LinkTypeName(linkType));
            }
            if (bytes.Length <= ipOffset) return lines;
            int version = bytes[ipOffset] >> 4;
            if (version == 4 && bytes.Length >= ipOffset + 20)
            {
                int ihl = (bytes[ipOffset] & 0x0f) * 4;
                int protocol = bytes[ipOffset + 9];
                AddField(lines, "ip.version", "4");
                AddField(lines, "ip.source", Ipv4(bytes, ipOffset + 12));
                AddField(lines, "ip.destination", Ipv4(bytes, ipOffset + 16));
                AddField(lines, "ip.header_length", ihl.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "ip.total_length", ReadU16(bytes, ipOffset + 2).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "ip.ttl", bytes[ipOffset + 8].ToString(CultureInfo.InvariantCulture));
                AddField(lines, "ip.protocol", ProtocolName(protocol));
                DecodeTransportFields(bytes, ipOffset + ihl, protocol, lines);
            }
            else if (version == 6 && bytes.Length >= ipOffset + 40)
            {
                int protocol = bytes[ipOffset + 6];
                AddField(lines, "ip.version", "6");
                AddField(lines, "ip.source", Ipv6(bytes, ipOffset + 8));
                AddField(lines, "ip.destination", Ipv6(bytes, ipOffset + 24));
                AddField(lines, "ip.payload_length", ReadU16(bytes, ipOffset + 4).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "ip.next_header", ProtocolName(protocol));
                AddField(lines, "ip.hop_limit", bytes[ipOffset + 7].ToString(CultureInfo.InvariantCulture));
                DecodeTransportFields(bytes, ipOffset + 40, protocol, lines);
            }
            else
            {
                AddField(lines, "ip.error", "unknown version " + version.ToString(CultureInfo.InvariantCulture));
            }
            return lines;
        }

        private static void DecodeTransportFields(byte[] bytes, int offset, int protocol, List<FieldValue> lines)
        {
            if (protocol == 6 && bytes.Length >= offset + 20)
            {
                int sport = ReadU16(bytes, offset);
                int dport = ReadU16(bytes, offset + 2);
                int headerLength = (bytes[offset + 12] >> 4) * 4;
                int payloadOffset = offset + headerLength;
                byte[] payload = payloadOffset <= bytes.Length ? Slice(bytes, payloadOffset, bytes.Length - payloadOffset) : new byte[0];
                AddField(lines, "tcp.source_port", sport.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.destination_port", dport.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.sequence", ReadU32(bytes, offset + 4).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.acknowledgment", ReadU32(bytes, offset + 8).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.header_length", headerLength.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.flags", TcpFlags(bytes[offset + 13]));
                AddField(lines, "tcp.window", ReadU16(bytes, offset + 14).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "tcp.payload_length", payload.Length.ToString(CultureInfo.InvariantCulture));
                AddApplicationFields(lines, payload, sport, dport);
            }
            else if (protocol == 17 && bytes.Length >= offset + 8)
            {
                int sport = ReadU16(bytes, offset);
                int dport = ReadU16(bytes, offset + 2);
                AddField(lines, "udp.source_port", sport.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "udp.destination_port", dport.ToString(CultureInfo.InvariantCulture));
                AddField(lines, "udp.length", ReadU16(bytes, offset + 4).ToString(CultureInfo.InvariantCulture));
                AddField(lines, "udp.checksum", "0x" + ReadU16(bytes, offset + 6).ToString("x4", CultureInfo.InvariantCulture));
                if (sport == 53 || dport == 53)
                {
                    List<FieldValue> dns = ParseDnsFields(bytes, offset + 8);
                    for (int i = 0; i < dns.Count; i++) lines.Add(dns[i]);
                }
            }
            else if (protocol == 1 || protocol == 58)
            {
                AddField(lines, "icmp.type", bytes.Length > offset ? bytes[offset].ToString(CultureInfo.InvariantCulture) : "-");
                AddField(lines, "icmp.code", bytes.Length > offset + 1 ? bytes[offset + 1].ToString(CultureInfo.InvariantCulture) : "-");
            }
        }

        private static void AddApplicationFields(List<FieldValue> lines, byte[] payload, int sport, int dport)
        {
            List<FieldValue> http = ParseHttpFields(payload);
            if (http.Count > 0)
            {
                for (int i = 0; i < http.Count; i++) lines.Add(http[i]);
                return;
            }
            List<FieldValue> tls = ParseTlsFields(payload);
            if (tls.Count > 0)
            {
                for (int i = 0; i < tls.Count; i++) lines.Add(tls[i]);
                return;
            }
            if (sport == 443 || dport == 443)
            {
                AddField(lines, "https.body", "encrypted; PCAP cannot show HTTP field values without MITM/decryption");
            }
        }

        private static string ApplicationSummary(byte[] payload)
        {
            List<FieldValue> http = ParseHttpFields(payload);
            if (http.Count > 0)
            {
                string method = FindField(http, "http.method");
                if (method.Length > 0) return "HTTP " + method + " " + FindHost(http) + FindField(http, "http.target");
                string code = FindField(http, "http.status_code");
                if (code.Length > 0) return ("HTTP " + code + " " + FindField(http, "http.reason")).Trim();
                return "HTTP";
            }
            List<FieldValue> tls = ParseTlsFields(payload);
            if (tls.Count > 0)
            {
                string sni = FindField(tls, "tls.sni");
                if (sni.Length > 0) return "TLS SNI " + sni;
                return "TLS " + FindField(tls, "tls.record_type");
            }
            return "";
        }

        private static List<FieldValue> ParseHttpFields(byte[] payload)
        {
            var fields = new List<FieldValue>();
            if (payload.Length == 0) return fields;
            int take = Math.Min(payload.Length, 8192);
            string text = Ascii(Slice(payload, 0, take)).Replace("\r\n", "\n");
            if (!LooksHttp(text)) return fields;
            int split = text.IndexOf("\n\n", StringComparison.Ordinal);
            string headerText = split >= 0 ? text.Substring(0, split) : text;
            string bodyText = split >= 0 ? text.Substring(split + 2) : "";
            string[] headerLines = headerText.Split(new char[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
            if (headerLines.Length == 0) return fields;
            string first = headerLines[0];
            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (first.StartsWith("HTTP/", StringComparison.OrdinalIgnoreCase))
            {
                string[] parts = first.Split(' ');
                AddField(fields, "http.version", parts.Length > 0 ? parts[0] : "");
                AddField(fields, "http.status_code", parts.Length > 1 ? parts[1] : "");
                AddField(fields, "http.reason", parts.Length > 2 ? JoinParts(parts, 2) : "");
            }
            else
            {
                string[] parts = first.Split(' ');
                AddField(fields, "http.method", parts.Length > 0 ? parts[0] : "");
                string target = parts.Length > 1 ? parts[1] : "";
                AddField(fields, "http.target", target);
                AddField(fields, "http.version", parts.Length > 2 ? parts[2] : "");
                int q = target.IndexOf('?');
                if (q >= 0) AddFormFields(fields, target.Substring(q + 1), "query");
            }
            for (int i = 1; i < headerLines.Length; i++)
            {
                int colon = headerLines[i].IndexOf(':');
                if (colon > 0)
                {
                    string name = headerLines[i].Substring(0, colon).Trim();
                    string value = headerLines[i].Substring(colon + 1).Trim();
                    headers[name] = value;
                    AddField(fields, "header." + name, value);
                }
            }
            string body = bodyText.Trim();
            if (body.Length > 0)
            {
                string contentType = headers.ContainsKey("Content-Type") ? headers["Content-Type"] : "";
                if (contentType.IndexOf("application/x-www-form-urlencoded", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    AddFormFields(fields, body, "form");
                }
                else if (contentType.IndexOf("json", StringComparison.OrdinalIgnoreCase) >= 0 || body.StartsWith("{", StringComparison.Ordinal))
                {
                    AddField(fields, "body.json", body);
                }
                else
                {
                    AddField(fields, "body.preview", body);
                }
            }
            return fields;
        }

        private static bool LooksHttp(string text)
        {
            string upper = text.Length > 8 ? text.Substring(0, 8).ToUpperInvariant() : text.ToUpperInvariant();
            return upper.StartsWith("GET ") || upper.StartsWith("POST ") || upper.StartsWith("PUT ") ||
                   upper.StartsWith("PATCH ") || upper.StartsWith("DELETE ") || upper.StartsWith("HEAD ") ||
                   upper.StartsWith("OPTIONS ") || upper.StartsWith("HTTP/");
        }

        private static void AddFormFields(List<FieldValue> fields, string value, string prefix)
        {
            string[] parts = value.Split('&');
            int limit = Math.Min(parts.Length, 80);
            for (int i = 0; i < limit; i++)
            {
                if (parts[i].Length == 0) continue;
                int eq = parts[i].IndexOf('=');
                string key = eq >= 0 ? parts[i].Substring(0, eq) : parts[i];
                string val = eq >= 0 ? parts[i].Substring(eq + 1) : "";
                AddField(fields, prefix + "." + DecodeUrl(key), DecodeUrl(val));
            }
        }

        private static List<FieldValue> ParseTlsFields(byte[] payload)
        {
            var fields = new List<FieldValue>();
            if (payload.Length < 5) return fields;
            int contentType = payload[0];
            if (payload[1] != 3 || !(contentType == 20 || contentType == 21 || contentType == 22 || contentType == 23)) return fields;
            AddField(fields, "tls.record_type", TlsRecordType(contentType));
            AddField(fields, "tls.record_version", "0x" + payload[1].ToString("x2", CultureInfo.InvariantCulture) + " 0x" + payload[2].ToString("x2", CultureInfo.InvariantCulture));
            AddField(fields, "tls.record_length", ReadU16(payload, 3).ToString(CultureInfo.InvariantCulture));
            if (contentType == 23)
            {
                AddField(fields, "tls.application_data", "encrypted");
                return fields;
            }
            if (contentType != 22 || payload.Length < 9) return fields;
            int handshakeType = payload[5];
            AddField(fields, "tls.handshake_type", TlsHandshakeType(handshakeType));
            if (handshakeType != 1) return fields;
            int cursor = 9;
            if (payload.Length < cursor + 34) return fields;
            AddField(fields, "tls.client_version", "0x" + payload[cursor].ToString("x2", CultureInfo.InvariantCulture) + " 0x" + payload[cursor + 1].ToString("x2", CultureInfo.InvariantCulture));
            cursor += 34;
            if (cursor >= payload.Length) return fields;
            cursor += 1 + payload[cursor];
            if (cursor + 2 > payload.Length) return fields;
            cursor += 2 + ReadU16(payload, cursor);
            if (cursor >= payload.Length) return fields;
            cursor += 1 + payload[cursor];
            if (cursor + 2 > payload.Length) return fields;
            int extensionsEnd = Math.Min(payload.Length, cursor + 2 + ReadU16(payload, cursor));
            cursor += 2;
            while (cursor + 4 <= extensionsEnd)
            {
                int type = ReadU16(payload, cursor);
                int length = ReadU16(payload, cursor + 2);
                int start = cursor + 4;
                int end = start + length;
                if (end > extensionsEnd) break;
                if (type == 0)
                {
                    string sni = ParseTlsSni(payload, start, end);
                    if (sni.Length > 0) AddField(fields, "tls.sni", sni);
                }
                if (type == 16)
                {
                    string alpn = ParseTlsAlpn(payload, start, end);
                    if (alpn.Length > 0) AddField(fields, "tls.alpn", alpn);
                }
                cursor = end;
            }
            return fields;
        }

        private static string ParseTlsSni(byte[] payload, int start, int end)
        {
            int cursor = start + 2;
            while (cursor + 3 <= end)
            {
                int nameType = payload[cursor++];
                int length = ReadU16(payload, cursor);
                cursor += 2;
                if (cursor + length > end) return "";
                string name = Ascii(Slice(payload, cursor, length));
                if (nameType == 0) return name;
                cursor += length;
            }
            return "";
        }

        private static string ParseTlsAlpn(byte[] payload, int start, int end)
        {
            int cursor = start + 2;
            var protocols = new List<string>();
            while (cursor < end)
            {
                int length = payload[cursor++];
                if (cursor + length > end) break;
                protocols.Add(Ascii(Slice(payload, cursor, length)));
                cursor += length;
            }
            return String.Join(", ", protocols.ToArray());
        }

        private static string ParseDnsSummary(byte[] bytes, int offset)
        {
            DnsQuestion question = ReadDnsQuestion(bytes, offset);
            if (question == null) return "DNS";
            return "DNS query " + question.Name;
        }

        private static List<FieldValue> ParseDnsFields(byte[] bytes, int offset)
        {
            var fields = new List<FieldValue>();
            if (bytes.Length < offset + 12) return fields;
            AddField(fields, "dns.transaction_id", "0x" + ReadU16(bytes, offset).ToString("x4", CultureInfo.InvariantCulture));
            AddField(fields, "dns.flags", "0x" + ReadU16(bytes, offset + 2).ToString("x4", CultureInfo.InvariantCulture));
            AddField(fields, "dns.questions", ReadU16(bytes, offset + 4).ToString(CultureInfo.InvariantCulture));
            DnsQuestion question = ReadDnsQuestion(bytes, offset);
            if (question != null)
            {
                AddField(fields, "dns.query.name", question.Name);
                AddField(fields, "dns.query.type", DnsTypeName(question.Type));
                AddField(fields, "dns.query.class", question.Class.ToString(CultureInfo.InvariantCulture));
            }
            return fields;
        }

        private static DnsQuestion ReadDnsQuestion(byte[] bytes, int offset)
        {
            if (bytes.Length < offset + 12 || ReadU16(bytes, offset + 4) < 1) return null;
            int cursor = offset + 12;
            var labels = new List<string>();
            for (int i = 0; i < 40 && cursor < bytes.Length; i++)
            {
                int length = bytes[cursor++];
                if (length == 0)
                {
                    if (cursor + 4 > bytes.Length || labels.Count == 0) return null;
                    var question = new DnsQuestion();
                    question.Name = String.Join(".", labels.ToArray());
                    question.Type = ReadU16(bytes, cursor);
                    question.Class = ReadU16(bytes, cursor + 2);
                    return question;
                }
                if ((length & 0xc0) != 0 || cursor + length > bytes.Length) return null;
                labels.Add(Ascii(Slice(bytes, cursor, length)));
                cursor += length;
            }
            return null;
        }

        public static string HexDump(byte[] bytes, int limit)
        {
            int count = Math.Min(bytes.Length, limit);
            var lines = new StringBuilder();
            for (int offset = 0; offset < count; offset += 16)
            {
                int chunk = Math.Min(16, count - offset);
                var hex = new StringBuilder();
                var text = new StringBuilder();
                for (int i = 0; i < chunk; i++)
                {
                    byte b = bytes[offset + i];
                    hex.Append(b.ToString("x2", CultureInfo.InvariantCulture)).Append(' ');
                    text.Append(b >= 0x20 && b <= 0x7e ? (char)b : '.');
                }
                lines.Append(offset.ToString("x4", CultureInfo.InvariantCulture)).Append("  ")
                    .Append(hex.ToString().PadRight(48)).Append("  ")
                    .AppendLine(text.ToString());
            }
            if (bytes.Length > limit)
            {
                lines.AppendLine("...[truncated]");
            }
            return lines.ToString();
        }

        public static string LinkTypeName(int value)
        {
            if (value == 1) return "Ethernet";
            if (value == 101) return "Raw IP";
            if (value == 228) return "IPv4";
            if (value == 229) return "IPv6";
            return "LINKTYPE " + value.ToString(CultureInfo.InvariantCulture);
        }

        private static void AddField(List<FieldValue> lines, string name, string value)
        {
            string clean = CompactValue(value);
            if (name.Length > 0 && clean.Length > 0) lines.Add(new FieldValue(name, clean));
        }

        private static string CompactValue(string value)
        {
            if (value == null) return "";
            string clean = value.Replace('\r', ' ').Replace('\n', ' ').Replace('\t', ' ').Trim();
            return clean.Length > 360 ? clean.Substring(0, 360) + "...[truncated]" : clean;
        }

        private static PacketDecoded Unknown(string info)
        {
            return new PacketDecoded("UNKNOWN", "-", "-", info);
        }

        private static uint ReadU32Endian(byte[] bytes, int offset, bool little)
        {
            if (little)
            {
                return (uint)(bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24));
            }
            return (uint)((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]);
        }

        private static int ReadU16Endian(byte[] bytes, int offset, bool little)
        {
            if (little) return bytes[offset] | (bytes[offset + 1] << 8);
            return (bytes[offset] << 8) | bytes[offset + 1];
        }

        private static int ReadU16(byte[] bytes, int offset)
        {
            return (bytes[offset] << 8) | bytes[offset + 1];
        }

        private static uint ReadU32(byte[] bytes, int offset)
        {
            return (uint)((bytes[offset] * 0x1000000) + ((bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]));
        }

        private static byte[] Slice(byte[] bytes, int offset, int length)
        {
            if (length <= 0) return new byte[0];
            byte[] result = new byte[length];
            Buffer.BlockCopy(bytes, offset, result, 0, length);
            return result;
        }

        private static string Ipv4(byte[] bytes, int offset)
        {
            return bytes[offset].ToString(CultureInfo.InvariantCulture) + "." +
                   bytes[offset + 1].ToString(CultureInfo.InvariantCulture) + "." +
                   bytes[offset + 2].ToString(CultureInfo.InvariantCulture) + "." +
                   bytes[offset + 3].ToString(CultureInfo.InvariantCulture);
        }

        private static string Ipv6(byte[] bytes, int offset)
        {
            string[] groups = new string[8];
            for (int i = 0; i < 16; i += 2)
            {
                groups[i / 2] = ReadU16(bytes, offset + i).ToString("x", CultureInfo.InvariantCulture);
            }
            return String.Join(":", groups);
        }

        private static string Mac(byte[] bytes)
        {
            string[] parts = new string[bytes.Length];
            for (int i = 0; i < bytes.Length; i++) parts[i] = bytes[i].ToString("x2", CultureInfo.InvariantCulture);
            return String.Join(":", parts);
        }

        private static string TcpFlags(int value)
        {
            var flags = new List<string>();
            if ((value & 0x01) != 0) flags.Add("FIN");
            if ((value & 0x02) != 0) flags.Add("SYN");
            if ((value & 0x04) != 0) flags.Add("RST");
            if ((value & 0x08) != 0) flags.Add("PSH");
            if ((value & 0x10) != 0) flags.Add("ACK");
            if ((value & 0x20) != 0) flags.Add("URG");
            return flags.Count == 0 ? "-" : String.Join(",", flags.ToArray());
        }

        private static string ProtocolName(int value)
        {
            if (value == 1) return "ICMP";
            if (value == 6) return "TCP";
            if (value == 17) return "UDP";
            if (value == 58) return "ICMPv6";
            return "IP/" + value.ToString(CultureInfo.InvariantCulture);
        }

        private static string TlsRecordType(int value)
        {
            if (value == 20) return "change_cipher_spec";
            if (value == 21) return "alert";
            if (value == 22) return "handshake";
            if (value == 23) return "application_data";
            return "type_" + value.ToString(CultureInfo.InvariantCulture);
        }

        private static string TlsHandshakeType(int value)
        {
            if (value == 1) return "client_hello";
            if (value == 2) return "server_hello";
            if (value == 11) return "certificate";
            if (value == 20) return "finished";
            return "handshake_" + value.ToString(CultureInfo.InvariantCulture);
        }

        private static string DnsTypeName(int value)
        {
            if (value == 1) return "A";
            if (value == 2) return "NS";
            if (value == 5) return "CNAME";
            if (value == 15) return "MX";
            if (value == 16) return "TXT";
            if (value == 28) return "AAAA";
            if (value == 33) return "SRV";
            if (value == 65) return "HTTPS";
            return value.ToString(CultureInfo.InvariantCulture);
        }

        private static string Ascii(byte[] bytes)
        {
            char[] chars = new char[bytes.Length];
            for (int i = 0; i < bytes.Length; i++) chars[i] = (char)bytes[i];
            return new string(chars);
        }

        private static string DecodeUrl(string value)
        {
            try
            {
                return Uri.UnescapeDataString((value ?? "").Replace("+", " "));
            }
            catch
            {
                return value;
            }
        }

        private static string FindField(List<FieldValue> fields, string name)
        {
            for (int i = 0; i < fields.Count; i++)
            {
                if (String.Equals(fields[i].Name, name, StringComparison.OrdinalIgnoreCase)) return fields[i].Value;
            }
            return "";
        }

        private static string FindHost(List<FieldValue> fields)
        {
            string host = FindField(fields, "header.Host");
            if (host.Length == 0) host = FindField(fields, "header.host");
            return host;
        }

        private static string JoinParts(string[] parts, int start)
        {
            var builder = new StringBuilder();
            for (int i = start; i < parts.Length; i++)
            {
                if (i > start) builder.Append(' ');
                builder.Append(parts[i]);
            }
            return builder.ToString();
        }

        private sealed class DnsQuestion
        {
            public string Name;
            public int Type;
            public int Class;
        }
    }
}
