import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.TFAC.CorssingVoid',
  appName: '零境启动器',
  webDir: 'dist',
  plugins: {
    SystemBars: {
      insetsHandling: "disable",
      hidden: true,
    },
  },
};

export default config;
