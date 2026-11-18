/**
 * 工具执行前的试用扣减检查。
 *
 * 真正的计数在服务端（lic.cjers.top），这里只是把 useToolOperation 的统一入口
 * 接到 /api/v1/license/consume 上：返回非 null 就拦下这次操作并提示。
 *
 * 拿不到授权状态（断网、服务端故障）时放行——客户的软件不能因为授权服务抖动而停摆。
 */
import { consumeTrial } from "@editor/license/licenseApi";

export function useCreditCheck(operationType?: string, _endpoint?: string) {
  return {
    checkCredits: async (_runtimeEndpoint?: string): Promise<string | null> => {
      const r = await consumeTrial(operationType ?? "");
      if (!r) return null; // 连不上授权服务：放行
      if (r.ok === false && !r.paid) {
        return "免费试用已结束，激活终身版后可继续使用";
      }
      return null;
    },
  };
}
