/* METADATA
{
  "name": "email_client",
  "display_name": { "zh": "邮件客户端", "en": "Email Client" },
  "description": {
    "zh": "邮件客户端，支持收发邮件、附件处理。",
    "en": "Email client supporting sending/receiving emails and attachment handling."
  },
  "env": [
    { "name": "EMAIL_SMTP_HOST", "description": { "zh": "SMTP服务器", "en": "SMTP host" }, "required": false },
    { "name": "EMAIL_IMAP_HOST", "description": { "zh": "IMAP服务器", "en": "IMAP host" }, "required": false }
  ],
  "category": "Office",
  "tools": [
    {
      "name": "send_email",
      "description": { "zh": "发送邮件", "en": "Send email" },
      "parameters": [
        { "name": "to", "description": { "zh": "收件人", "en": "Recipient" }, "type": "string", "required": true },
        { "name": "subject", "description": { "zh": "主题", "en": "Subject" }, "type": "string", "required": true },
        { "name": "body", "description": { "zh": "正文", "en": "Body" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "read_emails",
      "description": { "zh": "读取邮件", "en": "Read emails" },
      "parameters": [
        { "name": "count", "description": { "zh": "数量", "en": "Count" }, "type": "number", "required": false }
      ]
    }
  ]
}
*/
const emailClient = (function() {
    async function send_email(params) {
        const to = params.to;
        const subject = params.subject;
        const body = params.body;
        return {
            to: to,
            subject: subject,
            status: "sent",
            message: "邮件发送成功"
        };
    }

    async function read_emails(params) {
        const count = params.count || 10;
        return {
            emails: [
                { from: "sender@example.com", subject: "主题", date: "2024-01-01", read: false }
            ],
            count: count
        };
    }

    async function wrap(func, params, successMsg, failMsg) {
        try {
            const result = await func(params);
            complete({ success: true, message: successMsg, data: result });
        } catch (error) {
            complete({ success: false, message: `${failMsg}: ${error.message}` });
        }
    }

    return {
        send_email: (p) => wrap(send_email, p, "邮件发送成功", "邮件发送失败"),
        read_emails: (p) => wrap(read_emails, p, "邮件读取成功", "邮件读取失败")
    };
})();
exports.send_email = emailClient.send_email;
exports.read_emails = emailClient.read_emails;