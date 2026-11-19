import {esc, facts, screen} from './desktop.js';

export async function account(context) {
  const {api,current,onSessionLost} = context;
  const {show,listen,submit} = screen(context);
  const me = await api.get('/api/auth/me');
  show(`<section class="panel"><h2>站长资料</h2>${facts([['水站编号',me.stationId],['员工编号',me.staffId]])}
    <form id="my-profile" class="toolbar"><label>姓名（登录账号）<input name="nickname" value="${esc(me.nickname ?? '')}" required maxlength="50"></label><label>电话<input name="phone" type="tel" value="${esc(me.phone ?? '')}" maxlength="20"></label><button>保存资料</button></form></section>
    <form id="change-password" class="panel"><h2>修改密码</h2><p>保存后各端需要重新登录。</p>
    <label class="field">原密码<input name="old" type="password" autocomplete="current-password" maxlength="100" required></label>
    <label class="field">新密码<input name="new" type="password" autocomplete="new-password" minlength="6" maxlength="100" required></label>
    <label class="field">重复新密码<input name="again" type="password" autocomplete="new-password" minlength="6" maxlength="100" required></label><button>保存密码</button></form>`);
  listen('#my-profile','submit',async form => {
    const input = new FormData(form), nickname = input.get('nickname').trim(), phone = input.get('phone').trim();
    if (!nickname) throw new Error('请填写姓名');
    if (await submit('/api/auth/update-profile','POST',{nickname,phone},nickname === me.nickname ? '保存站长资料？' : `保存站长资料？下次请用「${nickname}」登录。`)) await account(context);
  });
  listen('#change-password','submit',async form => {
    const input = new FormData(form), oldPassword = input.get('old'), newPassword = input.get('new');
    if (newPassword.length < 6 || newPassword !== input.get('again')) throw new Error('请核对两次输入的新密码');
    if (await submit('/api/auth/change-password','POST',{oldPassword,newPassword},'修改密码并退出本账号的各端会话？') && current()) {
      api.clear(); onSessionLost('密码已修改，请重新登录');
    }
  });
}
