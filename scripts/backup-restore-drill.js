#!/usr/bin/env node
'use strict'
/** Manual isolated-schema tool. See docs/operations/05; all targets/artifacts retained. */
const { main } = require('./lib/backup-runner')
const argv = process.argv.slice(2)
if (!module.parent) main(argv)
module.exports = { main }
