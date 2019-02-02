# 👋 Pluot

Track your nutrition :)

## Client

### Develop

```bash
cd client && npm install && npm run start
```

**Note**: Currently we hit the production backend. You can change this in App.js

### Deploy

```bash
cd client && npm run build && firebase deploy
```

# Server

### Develop

**Note:** Ask Alex or Stepan for what you should have in your bash profile

To develop, we suggest IntelliJ with Cursive. You can run one repl and run (-main). You can add a second remote repl, to eval code.

### Deploy

Make sure you are on the heroku project. Add heroku to your local repo

```bash
heroku git:remote -a hipluot #
```

Then, deploy

```bash
git push heroku master
```

---

Copyright © 2019
