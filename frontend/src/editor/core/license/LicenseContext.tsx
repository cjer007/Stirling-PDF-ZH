import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import {
  applyLicense,
  consumeTrial,
  fetchStatus,
  redeemCode,
  type LicenseStatus,
} from "@editor/license/licenseApi";

interface LicenseContextValue {
  status: LicenseStatus | null;
  loading: boolean;
  /** 试用次数是否已用尽且未激活 */
  blocked: boolean;
  paywallOpen: boolean;
  openPaywall: () => void;
  closePaywall: () => void;
  refresh: () => Promise<void>;
  /** 执行一次扣减；返回 false 表示应拦下这次操作 */
  reserve: (feature: string) => Promise<boolean>;
  redeem: (code: string) => Promise<string>;
  apply: (email: string) => Promise<string>;
}

const Ctx = createContext<LicenseContextValue | null>(null);

const PRODUCT_NAME = "Stirling PDF 汉化版";
const DEFAULT_PRICE = 66;

export function LicenseProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<LicenseStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [paywallOpen, setPaywallOpen] = useState(false);
  const inflight = useRef(false);

  const refresh = useCallback(async () => {
    const s = await fetchStatus();
    if (s) {
      setStatus(s);
      // 后端降级（连不上授权服务）时不打断用户，按放行处理
      if (s.ok === false) setPaywallOpen(true);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const blocked = useMemo(() => {
    if (!status) return false;
    if (status.degraded) return false;
    if (status.paid) return false;
    return (status.trial_left ?? 0) <= 0;
  }, [status]);

  const reserve = useCallback(
    async (feature: string): Promise<boolean> => {
      if (inflight.current) return true;
      if (status?.paid) return true;
      if (blocked) {
        setPaywallOpen(true);
        return false;
      }
      inflight.current = true;
      try {
        const r = await consumeTrial(feature);
        // 连不上就放行：宁可少算一次，也不能让断网的客户用不了
        if (!r) return true;
        setStatus((prev) => ({ ...prev, ...r }));
        if (r.ok === false && !r.paid) {
          setPaywallOpen(true);
          return false;
        }
        return true;
      } finally {
        inflight.current = false;
      }
    },
    [status, blocked],
  );

  const redeem = useCallback(
    async (code: string): Promise<string> => {
      const r = await redeemCode(code);
      if (!r) return "连接授权服务失败，请检查网络后重试";
      if (r.ok) {
        await refresh();
        setPaywallOpen(false);
        return "激活成功，感谢支持！";
      }
      return r.msg || "激活失败";
    },
    [refresh],
  );

  const apply = useCallback(
    async (email: string): Promise<string> => {
      const r = await applyLicense(email);
      if (!r) return "连接授权服务失败，请检查网络后重试";
      if (r.ok) return r.msg || "申请已提交，2 小时内开通";
      return r.msg || "提交失败";
    },
    [],
  );

  const value: LicenseContextValue = {
    status,
    loading,
    blocked,
    paywallOpen,
    openPaywall: () => setPaywallOpen(true),
    closePaywall: () => setPaywallOpen(false),
    refresh,
    reserve,
    redeem,
    apply,
  };

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useLicense(): LicenseContextValue {
  const v = useContext(Ctx);
  if (!v) {
    // 组件可能在 Provider 外被渲染（独立页面/测试），给一个宽松的兜底
    return {
      status: null,
      loading: false,
      blocked: false,
      paywallOpen: false,
      openPaywall: () => {},
      closePaywall: () => {},
      refresh: async () => {},
      reserve: async () => true,
      redeem: async () => "授权模块未初始化",
      apply: async () => "授权模块未初始化",
    };
  }
  return v;
}

export const LICENSE_PRODUCT = {
  name: PRODUCT_NAME,
  price: DEFAULT_PRICE,
};
