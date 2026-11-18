/**
 * 授权服务前端封装。
 *
 * 所有函数都吞掉异常并返回 null —— 调用方据此决定放行还是拦下。
 * 后端 LicenseController 永远返回 200，业务状态放在 ok 字段里，
 * 所以这里不需要区分 HTTP 层错误与业务拒绝。
 */

const BASE = "/api/v1/license";

export interface LicenseStatus {
  ok: boolean;
  paid?: boolean;
  trial_left?: number;
  total?: number;
  price?: number;
  product?: string;
  product_name?: string;
  machine_code?: string;
  degraded?: boolean;
  msg?: string;
  code?: string;
}

async function call<T>(path: string, init?: RequestInit): Promise<T | null> {
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { "Content-Type": "application/json" },
      ...init,
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export async function fetchStatus(): Promise<LicenseStatus | null> {
  return call<LicenseStatus>("/status", { method: "GET" });
}

export async function consumeTrial(feature: string): Promise<LicenseStatus | null> {
  return call<LicenseStatus>("/consume", {
    method: "POST",
    body: JSON.stringify({ feature }),
  });
}

export async function redeemCode(code: string): Promise<LicenseStatus | null> {
  return call<LicenseStatus>("/redeem", {
    method: "POST",
    body: JSON.stringify({ code }),
  });
}

export async function applyLicense(email: string): Promise<LicenseStatus | null> {
  return call<LicenseStatus>("/apply", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}
