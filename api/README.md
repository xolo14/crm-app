# Syncpedia CRM Mobile API (Hostinger)

PHP 8+ REST API for the Android app and `https://crm.syncpedia.in`.

## Deploy on Hostinger

1. Upload the contents of `api/` to `public_html/api/` (or your subdomain docroot `/api`).
2. Copy `config.example.php` → `config.php` and set MySQL + `JWT_SECRET` (≥32 chars).
3. SSH or local: `cd api && composer install --no-dev`
4. In phpMyAdmin, run `migrations/2026_08_21_mobile_call_sync.sql` (skip ALTER lines that already exist).
5. Ensure `uploads/recordings/` and `storage/` are writable (755/750).

## Endpoints

| Method | Path | Auth |
|--------|------|------|
| POST | `/api/auth/login` | no |
| GET/POST | `/api/leads` | Bearer JWT |
| POST | `/api/calls/upload` | Bearer JWT (multipart) |
| GET | `/api/calls?page=1&limit=50` | Bearer JWT |
| POST | `/api/location` | Bearer JWT |
| GET | `/api/analytics?from=YYYY-MM-DD&to=YYYY-MM-DD` | Bearer JWT |

Login body: `{ "email", "password" }`  
Login response: `{ "token", "user": { "id", "name", "email", "role" } }`

All tenant data is scoped by `org_id` from the JWT — never from the client body.

## Security

- PDO prepared statements only
- `password_verify()` for logins; failed attempts rate-limited per email+IP
- CORS locked to `https://crm.syncpedia.in`
- `config.php` gitignored; never returned in API responses
