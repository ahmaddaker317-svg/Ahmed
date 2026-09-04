-- ADT Pro V130 - مزامنة أسماء أعضاء الفريق وصلاحيات الواجهات
-- شغّل هذا الملف مرة واحدة في Supabase SQL Editor قبل اختبار النسخة 1.30.

ALTER TABLE public.team_members
  ADD COLUMN IF NOT EXISTS can_manage_shortages boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS can_edit_debts boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS can_audit boolean NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION public.adt_update_my_user_name(
  p_team_id text,
  p_user_id text,
  p_user_name text
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
  v_user_name text := regexp_replace(btrim(coalesce(p_user_name, '')), '[[:space:]]+', ' ', 'g');
  v_role text;
BEGIN
  IF v_user_name = '' OR char_length(v_user_name) > 80 THEN
    RETURN false;
  END IF;

  SELECT tm.role
  INTO v_role
  FROM public.team_members tm
  WHERE tm.team_id::text = p_team_id
    AND tm.user_id = p_user_id
    AND (tm.status = 'approved' OR tm.role = 'manager')
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN false;
  END IF;

  UPDATE public.team_members
  SET user_name = v_user_name
  WHERE team_id::text = p_team_id
    AND user_id = p_user_id;

  IF v_role = 'manager' THEN
    UPDATE public.teams
    SET manager_name = v_user_name
    WHERE id::text = p_team_id;
  END IF;

  RETURN true;
END;
$function$;

REVOKE ALL ON FUNCTION public.adt_update_my_user_name(text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_update_my_user_name(text,text,text) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
