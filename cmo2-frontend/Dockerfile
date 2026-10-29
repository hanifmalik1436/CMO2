# Dev-mode container (hot reload). For production, build static assets and serve via Nginx.
FROM node:22-alpine
WORKDIR /app
# Matches package.json (always) and package-lock.json (if present).
COPY package*.json ./
RUN npm install
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
