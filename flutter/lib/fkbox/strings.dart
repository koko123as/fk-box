// FK-box: the few strings of FK-box's additions. FlClash's arb files and
// generated l10n stay as FlClash ships them, so these live here, in English
// and Simplified Chinese, following the app's current locale.
//
// Copyright (C) FK-box contributors. GPL-3.0, as FlClash.

import 'package:intl/intl.dart';

final fkStrings = FkStrings._();

class FkStrings {
  FkStrings._();

  bool get _zh => (Intl.defaultLocale ?? Intl.getCurrentLocale())
      .toLowerCase()
      .startsWith('zh');

  String _t(String en, String zh) => _zh ? zh : en;

  String get core => _t('Core', '内核');
  String get coreAuto => _t('Automatic', '自动');
  String coreNotAllowed(String core) =>
      _t("This profile's content can't run on $core", '该配置的内容不能由 $core 运行');
  String splitImported(int count) =>
      _t('Imported $count profiles from one file', '已从一个文件导入 $count 个配置');
  String legacyMigrated(int count) => _t(
    'Moved $count FK-box profiles into the profile list',
    '已将 $count 个 FK-box 旧配置迁移到配置列表',
  );

  // Tools
  String get fkboxTools => _t('FK-box tools', 'FK-box 工具');
  String get tailscale => 'Tailscale';
  String get ssh => _t('SSH terminal', 'SSH 终端');
  String get capture => _t('HTTP capture & rewrite', 'HTTP 抓包与改写');
  String get usbip => 'USB/IP';
  String get diagnostics => _t('Network diagnostics', '网络诊断');
  String get reports => _t('Diagnostic reports', '诊断报告');
  String get serviceNotRunning => _t('Start the service first', '请先启动服务');
  String get noEndpoints =>
      _t('The running profile has no endpoint of this kind', '当前运行的配置没有此类端点');
  String get refresh => _t('Refresh', '刷新');
  String get start => _t('Start', '开始');
  String get stop => _t('Stop', '停止');
  String get clear => _t('Clear', '清空');
  String get export => _t('Export', '导出');
  String get delete => _t('Delete', '删除');
  String get close => _t('Close', '关闭');
  String get online => _t('Online', '在线');
  String get offline => _t('Offline', '离线');
  String get exitNode => _t('Exit node', '出口节点');
  String get noExitNode => _t('None', '不使用');
  String get logout => _t('Log out', '退出登录');
  String get login => _t('Log in', '登录');
  String get ping => 'Ping';
  String get peers => _t('Peers', '节点');
  String get state => _t('State', '状态');
  String get username => _t('Username', '用户名');
  String get remember => _t('Remember', '记住');
  String get sessions => _t('Sessions', '会话');
  String get newSession => _t('New session', '新会话');
  String get duplicate => _t('Duplicate', '复制会话');
  String get certificate => _t('MITM certificate', 'MITM 证书');
  String get generate => _t('Generate', '生成');
  String get install => _t('Install', '安装');
  String get exportModule => _t('Export Magisk module', '导出 Magisk 模块');
  String get https => 'HTTPS';
  String get requests => _t('Requests', '请求');
  String get capturing => _t('Capturing', '正在抓包');
  String get logs => _t('Logs', '日志');
  String get devices => _t('Devices', '设备');
  String get servers => _t('Servers', '服务器');
  String get attach => _t('Attach', '连接');
  String get detach => _t('Detach', '断开');
  String get stun => _t('NAT type (STUN)', 'NAT 类型（STUN）');
  String get quality => _t('Network quality', '网络质量');
  String get server => _t('Server', '服务器');
  String get outbound => _t('Outbound', '出站');
  String get crash => _t('Crash', '崩溃');
  String get oom => 'OOM';
  String get battery => _t('Battery', '电量');
  String get empty => _t('Nothing here', '暂无内容');
  String get perAppManaged =>
      _t('FK-box managed per-app list (China apps)', 'FK-box 托管分应用列表（国内应用）');
  String get bootAutostart => _t('Start on boot', '开机自启');
  String get coreSwitches => _t('Cores', '内核');
  String get done => _t('Done', '完成');
  String get failed => _t('Failed', '失败');

  // Endpoints, Taildrop, remote control, intents
  String get openvpn => 'OpenVPN';
  String get openconnect => 'OpenConnect';
  String get authenticate => _t('Authenticate', '认证');
  String get authentication => _t('Authentication', '认证');
  String get password => _t('Password', '密码');
  String get verificationCode => _t('Verification code', '验证码');
  String get openSignIn => _t('Open sign-in page', '打开登录页面');
  String get signInBrowserOnly => _t(
    'This server signs in through a web page; open it in the browser.',
    '此服务器需通过网页登录，请在浏览器中打开。',
  );
  String get submit => _t('Submit', '提交');
  String get cancel => _t('Cancel', '取消');
  String get error => _t('Error', '错误');
  String get tunnel => _t('Tunnel', '隧道');
  String get taildrop => 'Taildrop';
  String get receivedFiles => _t('Received files', '收到的文件');
  String get transfers => _t('Transfers', '传输');
  String get sendFile => _t('Send file', '发送文件');
  String get sendWithTaildrop => _t('Send with Taildrop', '通过 Taildrop 发送');
  String get noRecipients => _t('No device can receive files', '没有可接收文件的设备');
  String get save => _t('Save', '保存');
  String get remote => _t('Remote control', '远程控制');
  String get addServer => _t('Add server', '添加服务器');
  String get name => _t('Name', '名称');
  String get address => _t('Address', '地址');
  String get secret => _t('Secret', '密钥');
  String get connect => _t('Connect', '连接');
  String get disconnect => _t('Disconnect', '断开');
  String get connected => _t('Connected', '已连接');
  String get connecting => _t('Connecting', '正在连接');
  String get edit => _t('Edit', '编辑');
  String get importProfile => _t('Import profile', '导入配置');
  String importProfileFrom(String source) =>
      _t('Add a profile from $source?', '从 $source 添加配置？');
}
