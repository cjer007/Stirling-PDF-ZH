/**
 * 授权服务前端封装。
 *
 * 所有函数都吞掉异常并返回 null —— 调用方据此决定放行还是拦下。
 * 后端 LicenseController 永远返回 200，业务状态放在 ok 字段里，
 * 所以这里不需要区分 HTTP 层错误与业务拒绝。
 *
 * 计次主体：每个浏览器生成一个持久化设备 ID（localStorage），随请求头带给后端。
 * 在线演示实例没法靠 IP 识别用户——移动网络出口 IP 会变，同一个人几次请求就会
 * 被算成几个新用户，试用额度形同虚设。
 */

const BASE = "/api/v1/license";
const DEVICE_KEY = "stirling_zh_device_id";

export interface LicenseStatus {
  ok: boolean;
  paid?: boolean;
  trial_left?: number;
  total?: number;
  price?: number;
  product?: string;
  product_name?: string;
  machine_code?: string;
  online_mode?: boolean;
  degraded?: boolean;
  msg?: string;
  code?: string;
}

function getDeviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_KEY);
    if (!id || id.length < 16) {
      const bytes = new Uint8Array(16);
      if (typeof crypto !== "undefined" && crypto.getRandomValues) {
        crypto.getRandomValues(bytes);
      } else {
        for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
      }
      id = Array.from(bytes)
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
      localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch {
    return "";
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T | null> {
  try {
    const deviceId = getDeviceId();
    const res = await fetch(`${BASE}${path}`, {
      headers: {
        "Content-Type": "application/json",
        ...(deviceId ? { "X-Device-Id": deviceId } : {}),
      },
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
