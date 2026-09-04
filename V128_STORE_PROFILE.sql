-- ADT Pro V128 - اسم المحل وعنوانه في الفاتورة
-- شغّل هذا الملف مرة واحدة في Supabase SQL Editor قبل اختبار حفظ بيانات المتجر.

ALTER TABLE public.teams
  ADD COLUMN IF NOT EXISTS invoice_store_name text,
  ADD COLUMN IF NOT EXISTS store_address text;

UPDATE public.teams
SET invoice_store_name = name
WHERE invoice_store_name IS NULL OR btrim(invoice_store_name) = '';

CREATE OR REPLACE FUNCTION public.adt_read_store_profile(
  p_team_id text,
  p_user_id text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
  v_allowed boolean := false;
  v_result jsonb;
BEGIN
  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND (tm.status = 'approved' OR tm.role = 'manager')
  ) INTO v_allowed;

  IF NOT v_allowed THEN
    RETURN NULL;
  END IF;

  SELECT jsonb_build_object(
    'store_name', coalesce(nullif(btrim(t.invoice_store_name), ''), t.name, 'المحل'),
    'store_address', coalesce(btrim(t.store_address), '')
  )
  INTO v_result
  FROM public.teams t
  WHERE t.id::text = p_team_id
  LIMIT 1;

  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.adt_update_store_profile(
  p_team_id text,
  p_user_id text,
  p_store_name text,
  p_store_address text DEFAULT ''
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
  v_allowed boolean := false;
  v_store_name text := btrim(coalesce(p_store_name, ''));
  v_store_address text := btrim(coalesce(p_store_address, ''));
BEGIN
  IF v_store_name = '' OR char_length(v_store_name) > 120 OR char_length(v_store_address) > 180 THEN
    RETURN false;
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND tm.role = 'manager'
  ) INTO v_allowed;

  IF NOT v_allowed THEN
    RETURN false;
  END IF;

  UPDATE public.teams
  SET invoice_store_name = v_store_name,
      store_address = v_store_address
  WHERE id::text = p_team_id;

  RETURN FOUND;
END;
$function$;

REVOKE ALL ON FUNCTION public.adt_read_store_profile(text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.adt_update_store_profile(text,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_read_store_profile(text,text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.adt_update_store_profile(text,text,text,text) TO anon, authenticated;
