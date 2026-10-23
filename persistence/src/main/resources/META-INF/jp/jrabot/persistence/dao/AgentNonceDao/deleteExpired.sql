DELETE FROM agent_nonce
WHERE expires_at < /* before */'2000-01-01'
ORDER BY expires_at
LIMIT /* limit */100
