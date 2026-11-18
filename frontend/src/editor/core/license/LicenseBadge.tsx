import { Badge } from "@mantine/core";
import { useLicense } from "@editor/license/LicenseContext";

/**
 * 右下角的授权状态徽标。未激活时显示剩余试用次数，激活后显示"终身版"。
 * 点击直接打开付费窗（已激活则无效）。
 */
export function LicenseBadge() {
  const { status, loading, openPaywall } = useLicense();

  if (loading || !status) return null;

  const paid = Boolean(status.paid);
  const degraded = Boolean(status.degraded);
  const left = status.trial_left ?? 0;

  const label = paid ? "终身版 · 已激活" : degraded ? "离线模式" : `免费试用 · 剩 ${left} 次`;
  const color = paid ? "teal" : degraded ? "gray" : left <= 1 ? "orange" : "blue";

  return (
    <Badge
      color={color}
      variant="filled"
      size="lg"
      radius="xl"
      onClick={paid ? undefined : openPaywall}
      style={{
        position: "fixed",
        right: 16,
        bottom: 16,
        zIndex: 300,
        cursor: paid ? "default" : "pointer",
        boxShadow: "0 6px 18px rgba(0,0,0,.18)",
      }}
    >
      {label}
    </Badge>
  );
}
