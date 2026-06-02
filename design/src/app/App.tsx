import { useState, useEffect } from 'react';
import {
  Power, MapPin, Wifi, Settings, User, MessageCircle,
  Clock, HardDrive, Smartphone, Shield, Router,
  Gift, CreditCard, Users, LogOut, Bell, FileText,
  ChevronRight, Activity, Zap, RefreshCw, HelpCircle, Copy
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

type Tab = 'home' | 'control' | 'account' | 'support';

export default function App() {
  // Auth states
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [tempVpnConnected, setTempVpnConnected] = useState(false);
  const [tempVpnConnecting, setTempVpnConnecting] = useState(false);
  const [skipTempVpn, setSkipTempVpn] = useState(false);
  const [waitingForTelegram, setWaitingForTelegram] = useState(false);

  const [activeTab, setActiveTab] = useState<Tab>('home');
  const [isConnected, setIsConnected] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [selectedServer, setSelectedServer] = useState(0);
  const [sessionTime, setSessionTime] = useState(0);
  const [hasSubscription] = useState(true);
  const [usedTrial] = useState(false);
  const [selectedOperator, setSelectedOperator] = useState('МТС');
  const [showTrafficDetails, setShowTrafficDetails] = useState(false);
  const [showOperatorSelect, setShowOperatorSelect] = useState(false);
  const [showDevices, setShowDevices] = useState(false);
  const [showBlockedDevices, setShowBlockedDevices] = useState(false);
  const [splitRoutingEnabled, setSplitRoutingEnabled] = useState(true);
  const [autoStartEnabled, setAutoStartEnabled] = useState(false);
  const [showReferralProgram, setShowReferralProgram] = useState(false);
  const [showLteTraffic, setShowLteTraffic] = useState(false);
  const [showPurchaseHistory, setShowPurchaseHistory] = useState(false);
  const [showNotifications, setShowNotifications] = useState(false);

  // Таймер сессии
  useEffect(() => {
    let interval: number;
    if (isConnected) {
      interval = window.setInterval(() => {
        setSessionTime(prev => prev + 1);
      }, 1000);
    } else {
      setSessionTime(0);
    }
    return () => clearInterval(interval);
  }, [isConnected]);

  const formatTime = (seconds: number) => {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  const servers = [
    { id: 0, name: 'SMART-Нидерланды', flag: '🇳🇱', type: 'SMART', desc: 'Без рекламы. Прямой доступ.', ping: 23, active: true },
    { id: 1, name: 'BRIDGE-Нидерланды', flag: '🇳🇱', type: 'BRIDGE', desc: 'Быстрый. Отличный пинг.', ping: 35, active: true },
    { id: 2, name: 'SMART-Германия', flag: '🇩🇪', type: 'SMART', desc: 'Быстрый. Хороший пинг.', ping: 42, active: true },
    { id: 3, name: 'SMART-Польша', flag: '🇵🇱', type: 'SMART', desc: 'Для игр. Быстрый.', ping: 28, active: true },
    { id: 4, name: 'SMART-Финляндия', flag: '🇫🇮', type: 'SMART', desc: 'Прямой доступ. Без рекламы.', ping: 31, active: true },
    { id: 5, name: 'BRIDGE-Финляндия', flag: '🇫🇮', type: 'BRIDGE', desc: 'Без рекламы. Лучший пинг.', ping: 29, active: true },
    { id: 6, name: 'SMART-Россия', flag: '🇷🇺', type: 'SMART', desc: 'Для российских сервисов.', ping: 8, active: true },
    { id: 7, name: '#1 LTE-Нидерланды', flag: '🇳🇱', type: 'LTE', desc: 'Когда глушат интернет.', ping: 45, active: true },
    { id: 8, name: '#2 LTE-Германия', flag: '🇩🇪', type: 'LTE', desc: 'Когда глушат интернет.', ping: 52, active: true },
  ];

  const handleConnect = async () => {
    if (isConnected) {
      setIsConnected(false);
      return;
    }

    setIsConnecting(true);
    await new Promise(resolve => setTimeout(resolve, 2000));
    setIsConnecting(false);
    setIsConnected(true);
  };

  const handleTempVpnConnect = async () => {
    if (tempVpnConnected) return;

    setTempVpnConnecting(true);
    await new Promise(resolve => setTimeout(resolve, 2000));
    setTempVpnConnecting(false);
    setTempVpnConnected(true);
  };

  const handleTelegramAuth = () => {
    setWaitingForTelegram(true);
    // Симуляция перехода в Telegram и возврата
    setTimeout(() => {
      setIsAuthenticated(true);
      setWaitingForTelegram(false);
    }, 3000);
  };

  const getPingColor = (ping: number) => {
    if (ping < 30) return 'text-[rgb(210,229,197)]';
    if (ping < 60) return 'text-[rgb(242,217,164)]';
    return 'text-[rgb(164,48,50)]';
  };

  const normalTraffic = 350; // GB
  const normalLimit = 1000; // GB (1 TB)
  const lteTraffic = 15; // GB
  const lteLimit = 40; // GB

  const ProgressBar = ({ value, max, label, unit, color }: { value: number, max: number, label: string, unit: string, color: string }) => {
    const percentage = (value / max) * 100;

    return (
      <div className="flex-1">
        <div className="flex items-baseline justify-between mb-2">
          <p className="text-neutral-400 text-[10px] font-black tracking-wider uppercase">{label}</p>
          <p className="text-white font-black text-lg">{value} <span className="text-neutral-500 text-xs font-bold">{unit}</span></p>
        </div>
        <div className="h-2.5 bg-neutral-700 rounded-full overflow-hidden">
          <div
            className={`h-full ${color.replace('text-', 'bg-')} transition-all duration-500 rounded-full`}
            style={{ width: `${percentage}%` }}
          />
        </div>
        <p className="text-neutral-600 text-[9px] mt-1.5 font-bold">из {max} {unit}</p>
      </div>
    );
  };

  // Onboarding/Auth Screen
  if (!isAuthenticated) {
    return (
      <div className="size-full bg-neutral-900 flex items-center justify-center p-4">
        <div className="w-full max-w-md h-full max-h-[812px] bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-3xl shadow-2xl border-2 border-neutral-700/50 overflow-hidden flex flex-col">
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center">
            {/* Logo */}
            <motion.div
              initial={{ scale: 0.8, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ duration: 0.5 }}
              className="mb-8"
            >
              <h1 className="text-white font-black text-3xl tracking-tight mb-2">REED VPN</h1>
              <p className="text-neutral-400 text-sm font-medium">Безопасный и быстрый VPN</p>
            </motion.div>

            {!waitingForTelegram ? (
              <motion.div
                initial={{ y: 20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                transition={{ delay: 0.3 }}
                className="w-full space-y-6"
              >
                <div className="bg-neutral-800/50 rounded-2xl p-6 border-2 border-neutral-700/50">
                  <p className="text-white text-sm font-medium mb-4 leading-relaxed">
                    Нажмите, чтобы подключиться к временному VPN для регистрации через Telegram
                  </p>

                  <motion.button
                    onClick={handleTempVpnConnect}
                    disabled={tempVpnConnecting || tempVpnConnected}
                    className={`w-full py-4 rounded-2xl font-black text-base tracking-tight transition-all ${
                      tempVpnConnected
                        ? 'bg-[rgb(155,210,0)] text-neutral-900'
                        : tempVpnConnecting
                        ? 'bg-neutral-700 text-neutral-400'
                        : 'bg-gradient-to-br from-[rgb(255,140,80)] to-[rgb(240,120,60)] text-white'
                    }`}
                    whileTap={!tempVpnConnected && !tempVpnConnecting ? { scale: 0.98 } : {}}
                    animate={tempVpnConnecting ? { scale: [1, 1.02, 1] } : {}}
                    transition={tempVpnConnecting ? { repeat: Infinity, duration: 1 } : {}}
                  >
                    {tempVpnConnecting
                      ? 'Подключаюсь...'
                      : tempVpnConnected
                      ? '✓ Вы подключены'
                      : 'Подключиться'}
                  </motion.button>
                </div>

                {!tempVpnConnected && !skipTempVpn && (
                  <motion.button
                    onClick={() => setSkipTempVpn(true)}
                    className="text-neutral-500 text-sm font-medium underline hover:text-neutral-400 transition-colors"
                    whileTap={{ scale: 0.98 }}
                  >
                    Мне не нужен временный VPN
                  </motion.button>
                )}

                {(tempVpnConnected || skipTempVpn) && (
                  <motion.div
                    initial={{ y: 20, opacity: 0 }}
                    animate={{ y: 0, opacity: 1 }}
                    transition={{ duration: 0.3 }}
                  >
                    <motion.button
                      onClick={handleTelegramAuth}
                      className="w-full py-4 bg-[rgb(155,210,0)] hover:bg-[rgb(180,230,30)] rounded-2xl text-neutral-900 font-black text-base tracking-tight shadow-lg transition-colors"
                      whileTap={{ scale: 0.98 }}
                    >
                      Зарегистрироваться через Telegram
                    </motion.button>
                    <p className="text-neutral-500 text-xs mt-3 font-medium">
                      После нажатия откроется Telegram бот. Нажмите Start для завершения регистрации.
                    </p>
                  </motion.div>
                )}

                {tempVpnConnected && (
                  <div className="bg-[rgb(155,210,0)]/10 rounded-xl p-4 border border-[rgb(155,210,0)]/30">
                    <p className="text-[rgb(155,210,0)] text-xs font-bold">
                      ℹ️ Временный VPN открывает только Telegram для регистрации
                    </p>
                  </div>
                )}
              </motion.div>
            ) : (
              <motion.div
                initial={{ scale: 0.8, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                className="w-full space-y-6"
              >
                <div className="relative">
                  <motion.div
                    className="w-20 h-20 mx-auto mb-6"
                    animate={{ rotate: 360 }}
                    transition={{ repeat: Infinity, duration: 2, ease: "linear" }}
                  >
                    <div className="w-full h-full border-4 border-neutral-700 border-t-[rgb(155,210,0)] rounded-full" />
                  </motion.div>
                  <h3 className="text-white font-black text-xl mb-2">Завершаем вход...</h3>
                  <p className="text-neutral-400 text-sm font-medium mb-6">
                    Откройте Telegram и нажмите Start в боте
                  </p>
                  <motion.button
                    onClick={() => {
                      setIsAuthenticated(true);
                      setWaitingForTelegram(false);
                    }}
                    className="text-[rgb(155,210,0)] text-sm font-bold hover:text-[rgb(180,230,30)] transition-colors"
                    whileTap={{ scale: 0.98 }}
                  >
                    Я нажал(а) Start
                  </motion.button>
                </div>
              </motion.div>
            )}
          </div>
        </div>
      </div>
    );
  }

  // Main App Screen
  return (
    <div className="size-full bg-neutral-900 flex items-center justify-center p-4">
      <div className="w-full max-w-md h-full max-h-[812px] bg-neutral-800 rounded-3xl shadow-2xl border border-neutral-700/50 overflow-hidden flex flex-col">

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {activeTab === 'home' && (
            <div className="p-6 pb-24">
              {/* Header */}
              <div className="mb-4">
                <h1 className="text-white font-black text-xl tracking-tight" style={{ fontFamily: 'system-ui, -apple-system, sans-serif' }}>
                  REED VPN
                </h1>
              </div>

              {/* Subscription Block */}
              {!hasSubscription && (
                <div className="mb-6">
                  <motion.button
                    className="w-full py-5 bg-gradient-to-r from-[rgb(255,120,60)] to-[rgb(255,160,100)] hover:opacity-90 rounded-3xl text-white font-black text-lg shadow-xl shadow-orange-500/30 transition-all"
                    whileTap={{ scale: 0.97 }}
                  >
                    {!usedTrial ? 'ПОЛУЧИТЬ 3 ДНЯ БЕСПЛАТНО' : 'КУПИТЬ VPN'}
                  </motion.button>
                  {usedTrial && (
                    <p className="text-center text-neutral-400 text-sm mt-3 font-bold">от 129 ₽/мес</p>
                  )}
                </div>
              )}

              {/* Traffic Bars + Timer */}
              {hasSubscription && (
                <div className="mb-6 bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-3xl p-5 border-2 border-neutral-700">
                  <div className="space-y-3 mb-5">
                    <ProgressBar
                      value={normalTraffic}
                      max={normalLimit}
                      label="Обычный трафик"
                      unit="ГБ"
                      color={normalTraffic / normalLimit < 0.7 ? 'text-[rgb(155,210,0)]' : normalTraffic / normalLimit < 0.9 ? 'text-[rgb(255,140,80)]' : 'text-[rgb(255,90,90)]'}
                    />
                    <ProgressBar
                      value={lteTraffic}
                      max={lteLimit}
                      label="LTE трафик"
                      unit="ГБ"
                      color={lteTraffic / lteLimit < 0.7 ? 'text-[rgb(155,210,0)]' : lteTraffic / lteLimit < 0.9 ? 'text-[rgb(255,140,80)]' : 'text-[rgb(255,90,90)]'}
                    />
                  </div>
                  <div className="text-center pt-4 border-t border-neutral-700">
                    <p className="text-white font-black text-xl tracking-tight mb-1">24Д 18Ч 42М</p>
                    <p className="text-neutral-500 text-[11px] font-bold uppercase tracking-wider">окончание подписки</p>
                  </div>
                </div>
              )}

              {/* Connection Button */}
              <div className="flex flex-col items-center mb-8">
                <motion.button
                  onClick={handleConnect}
                  disabled={isConnecting}
                  className={`w-48 h-48 rounded-full flex items-center justify-center transition-all duration-300 relative ${
                    isConnected
                      ? 'bg-gradient-to-br from-[rgb(155,210,0)] to-[rgb(180,230,30)] shadow-2xl shadow-[rgb(155,210,0)]/50'
                      : 'bg-gradient-to-br from-neutral-900 to-neutral-800 border-[6px] border-neutral-700'
                  }`}
                  whileTap={{ scale: 0.95 }}
                  animate={isConnecting ? { scale: [1, 1.05, 1] } : {}}
                  transition={isConnecting ? { repeat: Infinity, duration: 1 } : {}}
                >
                  {/* Timer Ring */}
                  {isConnected && (
                    <svg className="absolute inset-0 w-full h-full -rotate-90">
                      <circle
                        cx="96"
                        cy="96"
                        r="88"
                        stroke="rgba(0,0,0,0.2)"
                        strokeWidth="4"
                        fill="none"
                        strokeDasharray={2 * Math.PI * 88}
                        strokeDashoffset={2 * Math.PI * 88 - (sessionTime % 60) * (2 * Math.PI * 88 / 60)}
                      />
                    </svg>
                  )}

                  <div className="relative z-10 text-center">
                    <Power className={`w-16 h-16 mx-auto mb-3 ${isConnected ? 'text-neutral-900' : 'text-[rgb(155,210,0)]'}`} strokeWidth={2.5} />
                    {isConnected && (
                      <p className="text-neutral-900 text-base font-black tracking-wider">{formatTime(sessionTime)}</p>
                    )}
                  </div>

                  {isConnected && (
                    <>
                      <motion.div
                        className="absolute inset-0 border-[6px] border-[rgb(180,230,30)] rounded-full"
                        animate={{ scale: [1, 1.15], opacity: [0.4, 0] }}
                        transition={{ repeat: Infinity, duration: 2 }}
                      />
                      <motion.div
                        className="absolute inset-0 border-[6px] border-[rgb(180,230,30)] rounded-full"
                        animate={{ scale: [1, 1.15], opacity: [0.4, 0] }}
                        transition={{ repeat: Infinity, duration: 2, delay: 1 }}
                      />
                    </>
                  )}
                </motion.button>
              </div>

              {/* Locations Header */}
              <div className="flex items-center justify-between mb-5">
                <div className="flex items-center gap-2">
                  <MapPin className="w-6 h-6 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                  <span className="text-white font-black text-lg tracking-tight">СЕРВЕРЫ</span>
                </div>
                <div className="flex items-center gap-4">
                  <motion.button
                    className="text-[rgb(155,210,0)] text-xs font-black uppercase tracking-wider hover:text-[rgb(180,230,30)] transition-colors flex items-center gap-1.5"
                    whileTap={{ scale: 0.95 }}
                  >
                    <RefreshCw className="w-4 h-4" strokeWidth={2.5} />
                    Обновить
                  </motion.button>
                  <motion.button
                    className="text-[rgb(155,210,0)] text-xs font-black uppercase tracking-wider hover:text-[rgb(180,230,30)] transition-colors flex items-center gap-1.5"
                    whileTap={{ scale: 0.95 }}
                  >
                    <Activity className="w-4 h-4" strokeWidth={2.5} />
                    Тест
                  </motion.button>
                </div>
              </div>

              {/* Servers List */}
              <div className="space-y-2">
                {servers.map((server) => (
                  <motion.button
                    key={server.id}
                    onClick={() => setSelectedServer(server.id)}
                    className={`w-full p-4 rounded-2xl flex items-center justify-between transition-all ${
                      selectedServer === server.id
                        ? 'bg-gradient-to-r from-[rgb(155,210,0)]/20 to-[rgb(155,210,0)]/10 border-2 border-[rgb(155,210,0)]'
                        : 'bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 border-2 border-neutral-700/50'
                    }`}
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-center gap-3 flex-1">
                      <span className="text-3xl">{server.flag}</span>
                      <div className="text-left flex-1">
                        <p className={`font-black text-sm tracking-tight ${selectedServer === server.id ? 'text-[rgb(155,210,0)]' : 'text-white'}`}>
                          {server.name.toUpperCase()}
                        </p>
                        <p className="text-neutral-500 text-xs font-medium mt-0.5">{server.desc}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className={`text-sm font-bold ${selectedServer === server.id ? 'text-[rgb(155,210,0)]' : 'text-neutral-400'}`}>
                        {server.ping} мс
                      </span>
                      {isConnected && selectedServer === server.id && (
                        <motion.div
                          className="w-3 h-3 bg-[rgb(155,210,0)] rounded-full shadow-lg shadow-[rgb(155,210,0)]/60"
                          animate={{ opacity: [1, 0.3, 1] }}
                          transition={{ repeat: Infinity, duration: 1.5 }}
                        />
                      )}
                    </div>
                  </motion.button>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'control' && (
            <div className="p-6 pb-24">
              <h2 className="text-white text-xl font-black mb-6 tracking-tight">НАСТРОЙКИ</h2>

              <div className="space-y-3">
                {/* Operator Selection */}
                <motion.button
                  onClick={() => setShowOperatorSelect(!showOperatorSelect)}
                  className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                  whileTap={{ scale: 0.98 }}
                >
                  <div className="flex items-center gap-3">
                    <Router className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                    <div className="text-left">
                      <p className="text-white font-black text-sm tracking-tight">СМЕНИТЬ ОПЕРАТОРА</p>
                      <p className="text-neutral-500 text-xs font-medium mt-0.5">Текущий: {selectedOperator}</p>
                    </div>
                  </div>
                  <motion.div
                    animate={{ rotate: showOperatorSelect ? 90 : 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                  </motion.div>
                </motion.button>

                <AnimatePresence>
                {showOperatorSelect && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.3, ease: "easeInOut" }}
                    className="overflow-hidden"
                  >
                    <motion.div
                      className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-4 border-2 border-[rgb(155,210,0)]/30 mt-2"
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      transition={{ duration: 0.2, delay: 0.1 }}
                    >
                      <div className="space-y-2">
                        {['МТС', 'Мегафон', 'Yota', 'Билайн', 'Т2', 'Т-Мобайл', 'Ростелеком'].map((operator) => (
                          <motion.button
                            key={operator}
                            onClick={() => setSelectedOperator(operator)}
                            className={`w-full p-3 rounded-xl transition-all ${
                              selectedOperator === operator
                                ? 'bg-[rgb(155,210,0)]/20 border-2 border-[rgb(155,210,0)]'
                                : 'bg-neutral-800/50 hover:bg-neutral-800 border-2 border-neutral-700/50'
                            }`}
                            whileTap={{ scale: 0.98 }}
                          >
                            <p className={`font-black text-sm tracking-tight ${
                              selectedOperator === operator ? 'text-[rgb(155,210,0)]' : 'text-white'
                            }`}>
                              {operator}
                            </p>
                          </motion.button>
                        ))}
                      </div>
                    </motion.div>
                  </motion.div>
                )}
                </AnimatePresence>

                {/* Traffic Details */}
                <motion.button
                  onClick={() => setShowTrafficDetails(!showTrafficDetails)}
                  className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                  whileTap={{ scale: 0.98 }}
                >
                  <div className="flex items-center gap-3">
                    <HardDrive className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                    <span className="text-white font-black text-sm tracking-tight">ТРАФИК</span>
                  </div>
                  <motion.div
                    animate={{ rotate: showTrafficDetails ? 90 : 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                  </motion.div>
                </motion.button>

                <AnimatePresence>
                {showTrafficDetails && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.3 }}
                    className="overflow-hidden"
                  >
                    <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 border-2 border-[rgb(155,210,0)]/30 mt-2">
                    <div className="space-y-4">
                      <div className="bg-neutral-800/50 rounded-xl p-4 border border-neutral-700">
                        <p className="text-neutral-400 text-xs font-bold mb-2">ОБЫЧНЫЙ ТРАФИК</p>
                        <p className="text-white font-black text-2xl mb-1">{normalTraffic} ГБ</p>
                        <p className="text-neutral-500 text-xs font-medium">из {normalLimit} ГБ ({Math.round((normalTraffic / normalLimit) * 100)}%)</p>
                        <p className="text-neutral-600 text-xs mt-2">Осталось: {normalLimit - normalTraffic} ГБ</p>
                      </div>

                      <div className="bg-neutral-800/50 rounded-xl p-4 border border-neutral-700">
                        <p className="text-neutral-400 text-xs font-bold mb-2">LTE ТРАФИК</p>
                        <p className="text-white font-black text-2xl mb-1">{lteTraffic} ГБ</p>
                        <p className="text-neutral-500 text-xs font-medium">из {lteLimit} ГБ ({Math.round((lteTraffic / lteLimit) * 100)}%)</p>
                        <p className="text-neutral-600 text-xs mt-2">Осталось: {lteLimit - lteTraffic} ГБ</p>
                      </div>

                      <div className="bg-[rgb(155,210,0)]/10 rounded-xl p-4 border border-[rgb(155,210,0)]/30">
                        <p className="text-[rgb(155,210,0)] text-xs font-black mb-1">ОБНОВЛЕНИЕ ТРАФИКА</p>
                        <p className="text-white text-sm font-medium">Трафик обновляется каждое 1 число месяца</p>
                      </div>
                    </div>
                    </div>
                  </motion.div>
                )}
                </AnimatePresence>

                {/* Devices */}
                <motion.button
                  onClick={() => setShowDevices(!showDevices)}
                  className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                  whileTap={{ scale: 0.98 }}
                >
                  <div className="flex items-center gap-3">
                    <Smartphone className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                    <div className="text-left">
                      <p className="text-white font-black text-sm tracking-tight">УСТРОЙСТВА</p>
                      <p className="text-neutral-500 text-xs font-medium mt-0.5">занято 6 из 10</p>
                    </div>
                  </div>
                  <motion.div
                    animate={{ rotate: showDevices ? 90 : 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                  </motion.div>
                </motion.button>

                <AnimatePresence>
                {showDevices && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.3 }}
                    className="overflow-hidden"
                  >
                    <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-4 border-2 border-[rgb(155,210,0)]/30 mt-2">
                    <h3 className="text-white font-black text-sm tracking-tight mb-4">ПОДКЛЮЧЕННЫЕ УСТРОЙСТВА</h3>

                    <div className="space-y-3 mb-4">
                      {/* iPhone */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <Smartphone className="w-5 h-5 text-white" strokeWidth={2} />
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">iPhone 14 Pro Max</p>
                            <p className="text-neutral-500 text-xs">iOS • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>

                      {/* Samsung */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <Smartphone className="w-5 h-5 text-white" strokeWidth={2} />
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">Samsung S23</p>
                            <p className="text-neutral-500 text-xs">Android • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>

                      {/* iPad */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <rect x="4" y="2" width="16" height="20" rx="2" strokeWidth="2"/>
                            </svg>
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">iPad 5</p>
                            <p className="text-neutral-500 text-xs">iPadOS • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>

                      {/* MacBook */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <rect x="2" y="3" width="20" height="14" rx="2" strokeWidth="2"/>
                              <line x1="2" y1="20" x2="22" y2="20" strokeWidth="2"/>
                            </svg>
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">MacBook</p>
                            <p className="text-neutral-500 text-xs">macOS • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>

                      {/* Windows PC */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <svg className="w-6 h-6 text-white" fill="currentColor" viewBox="0 0 24 24">
                              <path d="M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451L0 20.699M10.949 12.6H24V24l-12.9-1.801"/>
                            </svg>
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">Windows PC</p>
                            <p className="text-neutral-500 text-xs">Windows • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>

                      {/* Android TV */}
                      <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-10 h-10 bg-neutral-700 rounded-xl flex items-center justify-center">
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <rect x="2" y="7" width="20" height="13" rx="2" strokeWidth="2"/>
                              <path d="M17 2l-5 5-5-5" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                            </svg>
                          </div>
                          <div className="flex-1">
                            <p className="text-white font-black text-sm">Android TV</p>
                            <p className="text-neutral-500 text-xs">Android TV • Активен</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button className="flex-1 py-2 bg-neutral-700 hover:bg-neutral-600 rounded-lg text-white text-xs font-bold transition-colors">
                            Заблокировать
                          </button>
                          <button className="flex-1 py-2 bg-[rgb(255,90,90)]/20 hover:bg-[rgb(255,90,90)]/30 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                            Удалить
                          </button>
                        </div>
                      </div>
                    </div>

                    {/* Blocked Devices Button */}
                    <button
                      onClick={() => setShowBlockedDevices(!showBlockedDevices)}
                      className="w-full py-3 bg-[rgb(255,90,90)]/10 hover:bg-[rgb(255,90,90)]/20 rounded-xl text-[rgb(255,90,90)] text-xs font-black uppercase tracking-wider transition-colors border-2 border-[rgb(255,90,90)]/30"
                    >
                      {showBlockedDevices ? 'Скрыть заблокированные' : 'Заблокированные устройства'}
                    </button>

                    {/* Blocked Devices List */}
                    {showBlockedDevices && (
                      <div className="mt-4 space-y-3">
                        <p className="text-neutral-500 text-xs font-black uppercase tracking-wider">Заблокированные</p>

                        {/* Blocked iPhone */}
                        <div className="bg-[rgb(255,90,90)]/10 rounded-xl p-3 border border-[rgb(255,90,90)]/30">
                          <div className="flex items-center gap-3 mb-3">
                            <div className="w-10 h-10 bg-[rgb(255,90,90)]/20 rounded-xl flex items-center justify-center">
                              <Smartphone className="w-5 h-5 text-[rgb(255,90,90)]" strokeWidth={2} />
                            </div>
                            <div className="flex-1">
                              <p className="text-white font-black text-sm">iPhone 12</p>
                              <p className="text-[rgb(255,90,90)] text-xs">Заблокирован</p>
                            </div>
                          </div>
                          <div className="flex gap-2">
                            <button className="flex-1 py-2 bg-[rgb(155,210,0)]/20 hover:bg-[rgb(155,210,0)]/30 rounded-lg text-[rgb(155,210,0)] text-xs font-bold transition-colors">
                              Разблокировать
                            </button>
                            <button className="flex-1 py-2 bg-[rgb(255,90,90)]/30 hover:bg-[rgb(255,90,90)]/40 rounded-lg text-[rgb(255,90,90)] text-xs font-bold transition-colors">
                              Удалить навсегда
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                    </div>
                  </motion.div>
                )}
                </AnimatePresence>

                <div className="pt-4 border-t-2 border-neutral-700 mt-4">
                  <motion.button
                    onClick={() => setSplitRoutingEnabled(!splitRoutingEnabled)}
                    className="w-full flex items-center justify-between p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl mb-3 border-2 border-neutral-700/50 transition-all hover:border-neutral-600"
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-center gap-3">
                      <motion.div
                        animate={{ color: splitRoutingEnabled ? 'rgb(155,210,0)' : 'rgb(255,140,80)' }}
                        transition={{ duration: 0.3 }}
                      >
                        <Wifi className="w-5 h-5" strokeWidth={2.5} />
                      </motion.div>
                      <span className="text-white font-black text-sm tracking-tight">SPLIT-ROUTING</span>
                    </div>
                    <motion.div
                      className="w-12 h-6 rounded-full relative cursor-pointer"
                      animate={{
                        backgroundColor: splitRoutingEnabled ? 'rgb(155,210,0)' : 'rgb(255,140,80)'
                      }}
                      transition={{ duration: 0.3 }}
                    >
                      <motion.div
                        className="absolute top-1 w-4 h-4 bg-neutral-900 rounded-full shadow-lg"
                        animate={{
                          x: splitRoutingEnabled ? 24 : 4
                        }}
                        transition={{ type: "spring", stiffness: 500, damping: 30 }}
                      />
                    </motion.div>
                  </motion.button>

                  <motion.button
                    onClick={() => setAutoStartEnabled(!autoStartEnabled)}
                    className="w-full flex items-center justify-between p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl mb-3 border-2 border-neutral-700/50 transition-all hover:border-neutral-600"
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-center gap-3">
                      <motion.div
                        animate={{ color: autoStartEnabled ? 'rgb(155,210,0)' : 'rgb(255,140,80)' }}
                        transition={{ duration: 0.3 }}
                      >
                        <Zap className="w-5 h-5" strokeWidth={2.5} />
                      </motion.div>
                      <span className="text-white font-black text-sm tracking-tight">АВТОЗАПУСК</span>
                    </div>
                    <motion.div
                      className="w-12 h-6 rounded-full relative cursor-pointer"
                      animate={{
                        backgroundColor: autoStartEnabled ? 'rgb(155,210,0)' : 'rgb(255,140,80)'
                      }}
                      transition={{ duration: 0.3 }}
                    >
                      <motion.div
                        className="absolute top-1 w-4 h-4 bg-neutral-900 rounded-full shadow-lg"
                        animate={{
                          x: autoStartEnabled ? 24 : 4
                        }}
                        transition={{ type: "spring", stiffness: 500, damping: 30 }}
                      />
                    </motion.div>
                  </motion.button>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'account' && (
            <div className="p-6 pb-24">
              <h2 className="text-white text-xl font-black mb-6 tracking-tight">ЛИЧНЫЙ КАБИНЕТ</h2>

              {/* Profile */}
              <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 mb-4 border-2 border-neutral-700/50">
                <div className="flex items-center gap-4 mb-4">
                  <div className="w-16 h-16 bg-gradient-to-br from-[rgb(155,210,0)] to-[rgb(180,230,30)] rounded-full flex items-center justify-center shadow-lg">
                    <User className="w-8 h-8 text-neutral-900" strokeWidth={2.5} />
                  </div>
                  <div>
                    <p className="text-white font-black text-base">Иван Иванов</p>
                    <p className="text-neutral-400 text-sm font-medium">@ivanov</p>
                  </div>
                </div>
                <div className="border-t-2 border-neutral-700 pt-4">
                  <p className="text-neutral-500 text-xs font-bold uppercase tracking-wider mb-1">Текущая подписка</p>
                  <p className="text-white font-black text-base">Индивидуальный тариф</p>
                  <p className="text-[rgb(155,210,0)] text-sm font-bold mt-1">заканчивается через 24 дня</p>
                </div>
              </div>

              {/* Actions */}
              <div className="space-y-3">
                <motion.button
                  className="w-full p-4 bg-[rgb(155,210,0)] hover:bg-[rgb(180,230,30)] rounded-2xl font-black text-neutral-900 text-base tracking-tight shadow-lg transition-colors"
                  whileTap={{ scale: 0.98 }}
                >
                  Продлить подписку
                </motion.button>

                {/* Referral Program */}
                <motion.button
                  onClick={() => setShowReferralProgram(!showReferralProgram)}
                  className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                  whileTap={{ scale: 0.98 }}
                >
                  <div className="flex items-center gap-3">
                    <Gift className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                    <span className="text-white font-black text-sm tracking-tight">РЕФЕРАЛЬНАЯ ПРОГРАММА</span>
                  </div>
                  <motion.div
                    animate={{ rotate: showReferralProgram ? 90 : 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                  </motion.div>
                </motion.button>

                <AnimatePresence>
                {showReferralProgram && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.3, ease: "easeInOut" }}
                    className="overflow-hidden"
                  >
                    <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 border-2 border-[rgb(155,210,0)]/30 space-y-4">
                      {/* Your Referral Code */}
                      <div>
                        <div className="flex items-center gap-2 mb-2">
                          <p className="text-neutral-400 text-xs font-bold uppercase tracking-wider">Ваш реферальный код</p>
                          <div className="group relative">
                            <HelpCircle className="w-4 h-4 text-neutral-600 cursor-help" strokeWidth={2} />
                            <div className="absolute left-0 bottom-6 w-64 bg-neutral-800 border-2 border-neutral-700 rounded-xl p-3 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-10">
                              <p className="text-white text-xs font-medium">Делитесь этим кодом с друзьями. Когда они оплачивают подписку, вам приходит 30% от их оплат!</p>
                            </div>
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <div className="flex-1 bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                            <p className="text-[rgb(155,210,0)] font-black text-base tracking-wider">REED2024XYZ</p>
                          </div>
                          <motion.button
                            className="p-3 bg-[rgb(155,210,0)]/20 hover:bg-[rgb(155,210,0)]/30 rounded-xl border border-[rgb(155,210,0)]/50 transition-colors"
                            whileTap={{ scale: 0.95 }}
                          >
                            <Copy className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                          </motion.button>
                        </div>
                      </div>

                      {/* Friend's Code */}
                      <div>
                        <p className="text-neutral-400 text-xs font-bold uppercase tracking-wider mb-2">Реферальный код друга</p>
                        <div className="bg-neutral-800/50 rounded-xl p-3 border border-neutral-700">
                          <p className="text-white font-black text-base tracking-wider">FRIEND123ABC</p>
                        </div>
                        <p className="text-neutral-500 text-xs mt-1 font-medium italic">Изменить код нельзя</p>
                      </div>

                      {/* Bonus Balance */}
                      <div className="bg-[rgb(155,210,0)]/10 rounded-xl p-4 border border-[rgb(155,210,0)]/30">
                        <p className="text-neutral-400 text-xs font-bold uppercase tracking-wider mb-1">Ваш бонусный баланс</p>
                        <p className="text-[rgb(155,210,0)] font-black text-3xl mb-2">1 250 ₽</p>
                        <p className="text-neutral-400 text-xs font-medium">Вывод доступен от 3000 бонусов. Обращаться в поддержку.</p>
                      </div>
                    </div>
                  </motion.div>
                )}
                </AnimatePresence>

                {/* LTE Traffic */}
                <motion.button
                  onClick={() => setShowLteTraffic(!showLteTraffic)}
                  className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                  whileTap={{ scale: 0.98 }}
                >
                  <div className="flex items-center gap-3">
                    <HardDrive className="w-5 h-5 text-[rgb(155,210,0)]" strokeWidth={2.5} />
                    <span className="text-white font-black text-sm tracking-tight">КУПИТЬ LTE-ТРАФИК</span>
                  </div>
                  <motion.div
                    animate={{ rotate: showLteTraffic ? 90 : 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                  </motion.div>
                </motion.button>

                <AnimatePresence>
                {showLteTraffic && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.3, ease: "easeInOut" }}
                    className="overflow-hidden"
                  >
                    <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 border-2 border-[rgb(155,210,0)]/30 space-y-4">
                      {/* Info */}
                      <div className="bg-[rgb(155,210,0)]/10 rounded-xl p-4 border border-[rgb(155,210,0)]/30 space-y-2 text-sm">
                        <p className="text-white font-medium">📦 Пакеты трафика переносятся на следующий месяц и суммируются с бесплатной квотой.</p>
                        <p className="text-white font-medium">📅 На вашем тарифе выделяется 40 ГБ трафика ежемесячно.</p>
                        <p className="text-white font-medium">🔄 Сброс трафика осуществляется 1 числа каждого месяца.</p>
                      </div>

                      {/* Current Traffic */}
                      <div className="bg-neutral-800/50 rounded-xl p-4 border border-neutral-700">
                        <p className="text-neutral-400 text-xs font-bold uppercase tracking-wider mb-3">📶 LTE-трафик</p>
                        <div className="space-y-2 text-sm">
                          <div className="flex justify-between">
                            <span className="text-neutral-400 font-medium">• Бесплатно:</span>
                            <span className="text-white font-bold">40 ГБ</span>
                          </div>
                          <div className="flex justify-between border-t border-neutral-700 pt-2">
                            <span className="text-white font-bold">Итого:</span>
                            <span className="text-[rgb(155,210,0)] font-black">40 ГБ</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-neutral-400 font-medium">Потреблено:</span>
                            <span className="text-white font-bold">0.0 / 40 ГБ</span>
                          </div>
                        </div>
                      </div>

                      {/* Packages */}
                      <div>
                        <p className="text-white font-black text-sm tracking-tight mb-3 uppercase">💰 Выберите размер пакета трафика</p>
                        <div className="space-y-2">
                          {[
                            { size: '5 ГБ', price: '29₽' },
                            { size: '10 ГБ', price: '49₽' },
                            { size: '20 ГБ', price: '79₽' },
                            { size: '50 ГБ', price: '149₽' },
                            { size: '100 ГБ', price: '259₽' }
                          ].map((pkg) => (
                            <motion.button
                              key={pkg.size}
                              className="w-full p-3 bg-neutral-800/50 hover:bg-neutral-800 rounded-xl border-2 border-neutral-700/50 hover:border-[rgb(155,210,0)]/50 transition-all flex items-center justify-between"
                              whileTap={{ scale: 0.98 }}
                            >
                              <span className="text-white font-black text-sm">{pkg.size}</span>
                              <span className="text-[rgb(155,210,0)] font-black text-base">{pkg.price}</span>
                            </motion.button>
                          ))}
                        </div>
                      </div>
                    </div>
                  </motion.div>
                )}
                </AnimatePresence>

                <div className="pt-4 border-t-2 border-neutral-700 mt-4 space-y-3">
                  {/* Purchase History */}
                  <motion.button
                    onClick={() => setShowPurchaseHistory(!showPurchaseHistory)}
                    className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-center gap-3">
                      <FileText className="w-5 h-5 text-neutral-400" strokeWidth={2.5} />
                      <span className="text-white font-black text-sm tracking-tight">ИСТОРИЯ ПОКУПОК</span>
                    </div>
                    <motion.div
                      animate={{ rotate: showPurchaseHistory ? 90 : 0 }}
                      transition={{ duration: 0.3 }}
                    >
                      <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                    </motion.div>
                  </motion.button>

                  <AnimatePresence>
                  {showPurchaseHistory && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: "auto" }}
                      exit={{ opacity: 0, height: 0 }}
                      transition={{ duration: 0.3, ease: "easeInOut" }}
                      className="overflow-hidden"
                    >
                      <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 border-2 border-neutral-700/50 space-y-3">
                        {/* Purchase items */}
                        {[
                          { date: '01.05.2026', item: 'Индивидуальный тариф - 1 месяц', amount: '199 ₽', status: 'success' },
                          { date: '15.04.2026', item: 'LTE трафик 20 ГБ', amount: '79 ₽', status: 'success' },
                          { date: '01.04.2026', item: 'Индивидуальный тариф - 1 месяц', amount: '199 ₽', status: 'success' },
                          { date: '10.03.2026', item: 'LTE трафик 50 ГБ', amount: '149 ₽', status: 'success' },
                          { date: '01.03.2026', item: 'Индивидуальный тариф - 3 месяца', amount: '549 ₽', status: 'success' }
                        ].map((purchase, index) => (
                          <div key={index} className="bg-neutral-800/50 rounded-xl p-4 border border-neutral-700">
                            <div className="flex justify-between items-start mb-2">
                              <div className="flex-1">
                                <p className="text-white font-black text-sm">{purchase.item}</p>
                                <p className="text-neutral-500 text-xs font-medium mt-1">{purchase.date}</p>
                              </div>
                              <div className="text-right">
                                <p className="text-[rgb(155,210,0)] font-black text-base">{purchase.amount}</p>
                                <p className="text-[rgb(155,210,0)] text-xs font-bold mt-1">✓ Оплачено</p>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </motion.div>
                  )}
                  </AnimatePresence>

                  {/* Notifications */}
                  <motion.button
                    onClick={() => setShowNotifications(!showNotifications)}
                    className="w-full p-4 bg-gradient-to-br from-neutral-900 to-neutral-800 hover:from-neutral-850 hover:to-neutral-750 rounded-2xl flex items-center justify-between border-2 border-neutral-700/50 transition-all"
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-center gap-3">
                      <Bell className="w-5 h-5 text-neutral-400" strokeWidth={2.5} />
                      <span className="text-white font-black text-sm tracking-tight">УВЕДОМЛЕНИЯ</span>
                    </div>
                    <motion.div
                      animate={{ rotate: showNotifications ? 90 : 0 }}
                      transition={{ duration: 0.3 }}
                    >
                      <ChevronRight className="w-5 h-5 text-neutral-600" strokeWidth={2.5} />
                    </motion.div>
                  </motion.button>

                  <AnimatePresence>
                  {showNotifications && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: "auto" }}
                      exit={{ opacity: 0, height: 0 }}
                      transition={{ duration: 0.3, ease: "easeInOut" }}
                      className="overflow-hidden"
                    >
                      <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-5 border-2 border-neutral-700/50 space-y-3">
                        {/* Notification items */}
                        {[
                          {
                            date: '2 часа назад',
                            title: 'Успешное подключение',
                            text: 'Вы подключились к серверу SMART-Нидерланды',
                            type: 'success'
                          },
                          {
                            date: 'Сегодня, 10:30',
                            title: 'Напоминание о подписке',
                            text: 'Ваша подписка закончится через 3 дня. Продлите, чтобы не потерять доступ.',
                            type: 'warning'
                          },
                          {
                            date: 'Вчера, 15:20',
                            title: 'Новое устройство',
                            text: 'Обнаружен вход с нового устройства: iPhone 14 Pro Max',
                            type: 'info'
                          },
                          {
                            date: '29.05.2026',
                            title: 'Реферальный бонус',
                            text: 'Вам начислено 60 ₽ за оплату друга по реферальной программе',
                            type: 'success'
                          },
                          {
                            date: '25.05.2026',
                            title: 'LTE трафик закончился',
                            text: 'Ваш LTE трафик израсходован. Докупите пакет для продолжения работы.',
                            type: 'alert'
                          }
                        ].map((notification, index) => (
                          <div key={index} className={`rounded-xl p-4 border-2 ${
                            notification.type === 'success' ? 'bg-[rgb(155,210,0)]/10 border-[rgb(155,210,0)]/30' :
                            notification.type === 'warning' ? 'bg-[rgb(255,140,80)]/10 border-[rgb(255,140,80)]/30' :
                            notification.type === 'alert' ? 'bg-[rgb(164,48,50)]/10 border-[rgb(164,48,50)]/30' :
                            'bg-neutral-800/50 border-neutral-700'
                          }`}>
                            <div className="flex items-start gap-3">
                              <div className={`w-2 h-2 rounded-full mt-2 flex-shrink-0 ${
                                notification.type === 'success' ? 'bg-[rgb(155,210,0)]' :
                                notification.type === 'warning' ? 'bg-[rgb(255,140,80)]' :
                                notification.type === 'alert' ? 'bg-[rgb(164,48,50)]' :
                                'bg-neutral-500'
                              }`} />
                              <div className="flex-1">
                                <div className="flex justify-between items-start mb-1">
                                  <p className="text-white font-black text-sm">{notification.title}</p>
                                  <p className="text-neutral-500 text-xs font-medium whitespace-nowrap ml-2">{notification.date}</p>
                                </div>
                                <p className="text-neutral-400 text-xs font-medium leading-relaxed">{notification.text}</p>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </motion.div>
                  )}
                  </AnimatePresence>

                  <motion.button
                    onClick={() => setIsAuthenticated(false)}
                    className="w-full p-4 bg-[rgb(164,48,50)]/10 hover:bg-[rgb(164,48,50)]/20 rounded-2xl flex items-center gap-3 border-2 border-[rgb(164,48,50)]/30 transition-colors"
                    whileTap={{ scale: 0.98 }}
                  >
                    <LogOut className="w-5 h-5 text-[rgb(164,48,50)]" strokeWidth={2.5} />
                    <span className="text-[rgb(164,48,50)] font-black text-sm tracking-tight">ВЫЙТИ ИЗ АККАУНТА</span>
                  </motion.button>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'support' && (
            <div className="p-6 pb-24">
              <h2 className="text-white text-xl font-black mb-6 tracking-tight">ПОДДЕРЖКА</h2>

              <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-8 border-2 border-neutral-700/50 text-center">
                <MessageCircle className="w-20 h-20 text-[rgb(155,210,0)] mx-auto mb-6" strokeWidth={2} />
                <h3 className="text-white font-black text-lg mb-3 tracking-tight">ЧАТ С ПОДДЕРЖКОЙ</h3>
                <p className="text-neutral-400 text-sm mb-8 font-medium">
                  Напишите нам в Telegram, и мы быстро вам поможем
                </p>
                <motion.button
                  className="w-full py-4 bg-[rgb(155,210,0)] hover:bg-[rgb(180,230,30)] rounded-2xl text-neutral-900 font-black text-base tracking-tight shadow-lg transition-colors"
                  whileTap={{ scale: 0.98 }}
                >
                  Открыть чат в Telegram
                </motion.button>
              </div>
            </div>
          )}
        </div>

        {/* Tab Bar */}
        <div className="bg-neutral-900/95 backdrop-blur-xl border-t-2 border-neutral-700 px-6 py-4 safe-area-inset-bottom">
          <div className="flex items-center justify-around">
            <motion.button
              onClick={() => setActiveTab('home')}
              className={`flex flex-col items-center gap-1.5 transition-all ${
                activeTab === 'home' ? 'text-[rgb(155,210,0)]' : 'text-neutral-600'
              }`}
              whileTap={{ scale: 0.9 }}
            >
              <Shield className="w-6 h-6" strokeWidth={activeTab === 'home' ? 2.5 : 2} />
              <span className="text-[10px] font-bold uppercase tracking-wider">Главная</span>
            </motion.button>

            <motion.button
              onClick={() => setActiveTab('control')}
              className={`flex flex-col items-center gap-1.5 transition-all ${
                activeTab === 'control' ? 'text-[rgb(255,140,80)]' : 'text-neutral-600'
              }`}
              whileTap={{ scale: 0.9 }}
            >
              <Settings className="w-6 h-6" strokeWidth={activeTab === 'control' ? 2.5 : 2} />
              <span className="text-[10px] font-bold uppercase tracking-wider">Настройки</span>
            </motion.button>

            <motion.button
              onClick={() => setActiveTab('account')}
              className={`flex flex-col items-center gap-1.5 transition-all ${
                activeTab === 'account' ? 'text-[rgb(155,210,0)]' : 'text-neutral-600'
              }`}
              whileTap={{ scale: 0.9 }}
            >
              <User className="w-6 h-6" strokeWidth={activeTab === 'account' ? 2.5 : 2} />
              <span className="text-[10px] font-bold uppercase tracking-wider">Профиль</span>
            </motion.button>

            <motion.button
              onClick={() => setActiveTab('support')}
              className={`flex flex-col items-center gap-1.5 transition-all ${
                activeTab === 'support' ? 'text-[rgb(255,140,80)]' : 'text-neutral-600'
              }`}
              whileTap={{ scale: 0.9 }}
            >
              <MessageCircle className="w-6 h-6" strokeWidth={activeTab === 'support' ? 2.5 : 2} />
              <span className="text-[10px] font-bold uppercase tracking-wider">Помощь</span>
            </motion.button>
          </div>
        </div>
      </div>
    </div>
  );
}