// 环境配置
const ENV = {
  DEV: 'dev',
  STAGING: 'staging',
  PROD: 'prod'
}

const currentEnv = __wxConfig.envVersion === 'release' ? ENV.PROD : ENV.DEV

module.exports = {
  ENV,
  currentEnv
}
