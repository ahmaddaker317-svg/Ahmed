-- Run once in Supabase SQL Editor. Publishing requires the verified developer PIN.
CREATE TABLE IF NOT EXISTS public.adt_app_releases (
    id integer PRIMARY KEY CHECK (id = 1),
    version_code integer NOT NULL CHECK (version_code > 0),
    version_name text NOT NULL,
    download_url text,
    notes text NOT NULL DEFAULT '',
    required boolean NOT NULL DEFAULT false,
    published_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO public.adt_app_releases (id, version_code, version_name, notes)
VALUES (1, 191, '1.91.0', '')
ON CONFLICT (id) DO NOTHING;

ALTER TABLE public.adt_app_releases ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS adt_app_releases_read ON public.adt_app_releases;
CREATE POLICY adt_app_releases_read ON public.adt_app_releases
FOR SELECT TO anon, authenticated USING (true);
REVOKE ALL ON public.adt_app_releases FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.adt_app_releases TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.adt_admin_publish_app_release(
    p_dev_pin text,
    p_device_id text,
    p_version_code integer,
    p_download_url text,
    p_notes text DEFAULT ''
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp AS $$
DECLARE current_code integer;
BEGIN
    IF public.adt_verify_dev_pin(p_dev_pin, p_device_id) IS DISTINCT FROM true THEN
        RETURN false;
    END IF;
    IF p_version_code IS NULL OR p_version_code <= 0 OR
       p_download_url IS NULL OR p_download_url !~* '^https://[^[:space:]]+$' THEN
        RETURN false;
    END IF;
    SELECT version_code INTO current_code FROM public.adt_app_releases WHERE id = 1 FOR UPDATE;
    IF p_version_code <= COALESCE(current_code, 0) THEN RETURN false; END IF;
    INSERT INTO public.adt_app_releases
      (id, version_code, version_name, download_url, notes, published_at)
    VALUES (1, p_version_code, '1.' || p_version_code || '.0', p_download_url,
            left(coalesce(p_notes, ''), 500), now())
    ON CONFLICT (id) DO UPDATE SET
      version_code=EXCLUDED.version_code,
      version_name=EXCLUDED.version_name,
      download_url=EXCLUDED.download_url,
      notes=EXCLUDED.notes,
      published_at=EXCLUDED.published_at;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.adt_admin_publish_app_release(text,text,integer,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_admin_publish_app_release(text,text,integer,text,text) TO anon, authenticated;
