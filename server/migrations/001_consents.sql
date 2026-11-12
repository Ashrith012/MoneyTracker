-- Apply AFTER Better Auth CLI migration. Use the server role only.
CREATE TABLE IF NOT EXISTS consent_records (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id text NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
 kind text NOT NULL CHECK (kind IN ('gmail','analytics','privacy')),
 granted boolean NOT NULL,
 version text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE consent_records ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON consent_records FROM anon, authenticated;
-- Better Auth owns its schema. No financial records belong in Supabase.

-- Run as the same dedicated owner used to migrate Better Auth. Keep these tables server-only.
DO $$
DECLARE tbl text;
BEGIN
 FOREACH tbl IN ARRAY ARRAY['user','session','account','verification','rateLimit'] LOOP
  IF to_regclass(format('public.%I',tbl)) IS NOT NULL THEN
   EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('REVOKE ALL ON public.%I FROM anon, authenticated',tbl);
  END IF;
 END LOOP;
END $$;
