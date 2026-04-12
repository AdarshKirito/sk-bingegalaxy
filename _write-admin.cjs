const fs = require('fs');
const path = require('path');
const target = path.join(__dirname, 'frontend/src/pages/AdminUsersConfig.jsx');
const content = fs.readFileSync(path.join(__dirname, '_admin-content.txt'), 'utf8');
fs.writeFileSync(target, content, 'utf8');
console.log('Written ' + content.length + ' chars to AdminUsersConfig.jsx');
