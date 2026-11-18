import { useState } from "react";
import { Badge, Button, Code, CopyButton, Group, Modal, Stack, Tabs, Text, TextInput } from "@mantine/core";
import { useLicense, LICENSE_PRODUCT } from "@editor/license/LicenseContext";

const PAY_QR = "https://lic.cjers.top/pay_qr.png";

function MachineCodeRow() {
  const { status } = useLicense();
  const code = status?.machine_code || "";
  return (
    <div>
      <Text size="xs" c="dimmed" mb={4}>
        本机机器码
      </Text>
      <Group gap="xs" wrap="nowrap">
        <Code style={{ flex: 1, wordBreak: "break-all", fontSize: 11 }}>{code || "读取中…"}</Code>
        <CopyButton value={code}>
          {({ copied, copy }) => (
            <Button size="compact-sm" variant="light" onClick={copy} disabled={!code}>
              {copied ? "已复制" : "复制"}
            </Button>
          )}
        </CopyButton>
      </Group>
    </div>
  );
}

export function PaywallModal() {
  const { paywallOpen, closePaywall, status, redeem, apply } = useLicense();
  const [code, setCode] = useState("");
  const [email, setEmail] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const price = status?.price ?? LICENSE_PRODUCT.price;

  const doRedeem = async () => {
    if (!code.trim()) return;
    setBusy(true);
    setMsg(await redeem(code.trim()));
    setBusy(false);
  };

  const doApply = async () => {
    if (!email.trim()) {
      setMsg("请填写用于接收激活邮件的邮箱");
      return;
    }
    setBusy(true);
    setMsg(await apply(email.trim()));
    setBusy(false);
  };

  return (
    <Modal
      opened={paywallOpen}
      onClose={closePaywall}
      size="lg"
      centered
      title={
        <Group gap="xs">
          <Text fw={700} size="lg">
            免费试用已结束
          </Text>
          <Badge color="red" variant="light">
            未激活
          </Badge>
        </Group>
      }
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          这款工具的全部处理都在你自己的电脑上完成，文件从不上传。免费试用额度已经用完，
          激活终身版后不再限次数、不限文件大小，永久可用。
        </Text>

        <div
          style={{
            background: "var(--mantine-color-dark-6)",
            borderRadius: 12,
            padding: "16px 18px",
          }}
        >
          <Group justify="space-between" align="baseline">
            <div>
              <Text size="xs" c="dimmed">
                {LICENSE_PRODUCT.name} · 终身版
              </Text>
              <Text fw={800} size="32px" style={{ color: "var(--mantine-color-red-5)" }}>
                ¥{price}
              </Text>
            </div>
            <Stack gap={2} align="flex-end">
              <Badge variant="light">一次买断</Badge>
              <Badge variant="light" color="teal">
                单机永久授权
              </Badge>
            </Stack>
          </Group>
        </div>

        <MachineCodeRow />

        <Tabs defaultValue="pay">
          <Tabs.List grow>
            <Tabs.Tab value="pay">扫码付款</Tabs.Tab>
            <Tabs.Tab value="apply">邮箱申请开通</Tabs.Tab>
            <Tabs.Tab value="code">兑换码激活</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="pay" pt="md">
            <Stack align="center" gap="xs">
              <img
                src={PAY_QR}
                alt="付款二维码"
                style={{ width: 200, height: 200, objectFit: "contain", borderRadius: 8 }}
              />
              <Text size="sm">
                支付宝 / 微信扫码付 <b>¥{price}</b>
              </Text>
              <Text size="xs" c="dimmed" ta="center">
                付款后到「邮箱申请开通」填邮箱，或把机器码和付款截图发给客服，2 小时内开通。
              </Text>
            </Stack>
          </Tabs.Panel>

          <Tabs.Panel value="apply" pt="md">
            <Stack gap="sm">
              <TextInput
                label="接收激活码的邮箱"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.currentTarget.value)}
              />
              <Button loading={busy} onClick={doApply}>
                提交申请
              </Button>
              <Text size="xs" c="dimmed">
                会把这个机器码和你的邮箱一起登记，激活后重启软件即可。
              </Text>
            </Stack>
          </Tabs.Panel>

          <Tabs.Panel value="code" pt="md">
            <Stack gap="sm">
              <TextInput
                label="兑换码"
                placeholder="M-XXXXXXXXXXXXXXXX"
                value={code}
                onChange={(e) => setCode(e.currentTarget.value)}
              />
              <Button loading={busy} onClick={doRedeem}>
                激活
              </Button>
            </Stack>
          </Tabs.Panel>
        </Tabs>

        {msg && (
          <Text size="sm" c={msg.includes("成功") ? "teal" : "red"}>
            {msg}
          </Text>
        )}
      </Stack>
    </Modal>
  );
}
