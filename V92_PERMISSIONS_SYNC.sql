-- ADT Pro V92 - مزامنة عرض صلاحيات العضو مع القيم الحقيقية في team_members
-- شغّل الملف مرة واحدة فقط في Supabase SQL Editor.

CREATE OR REPLACE FUNCTION public.adt_read_member_permissions(
  p_team_id text,
  p_manager_user_id text,
  p_member_id text
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
  -- يسمح لمدير الفريق أو المدير المساعد المعتمد بقراءة الحالة الحقيقية للصلاحيات.
  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.team_id::text = p_team_id
      AND tm.user_id = p_manager_user_id
      AND tm.status = 'approved'
      AND tm.role IN ('manager','sub_manager')
  ) INTO v_allowed;

  IF NOT v_allowed THEN
    RETURN NULL;
  END IF;

  SELECT jsonb_build_object(
    'id', tm.id,
    'user_name', tm.user_name,
    'role', tm.role,
    'can_edit_products', coalesce(tm.can_edit_products,false),
    'can_manage_shortages', coalesce(tm.can_manage_shortages,false),
    'can_edit_debts', coalesce(tm.can_edit_debts,false),
    'can_audit', coalesce(tm.can_audit,false),
    'can_view_cost', coalesce(tm.can_view_cost,false),
    'can_create_invoices', coalesce(tm.can_create_invoices,false),
    'can_edit_sell_price', coalesce(tm.can_edit_sell_price,false),
    'can_manage_catalog', coalesce(tm.can_manage_catalog,false)
  )
  INTO v_result
  FROM public.team_members tm
  WHERE tm.team_id::text = p_team_id
    AND tm.id::text = p_member_id
  LIMIT 1;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.adt_read_member_permissions(text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_read_member_permissions(text,text,text) TO anon, authenticated;
