-- ADT Stock v184 stable add-on: حذف تصفية المبيعات
-- شغّل هذا الكود مرة واحدة في Supabase SQL Editor.
create or replace function public.adt_daily_sales_delete_settlement(
  p_team_id text,
  p_user_id text,
  p_settlement_id text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  if not public.adt_daily_sales_manager_allowed(p_team_id,p_user_id) then
    raise exception 'NOT_ALLOWED';
  end if;

  if coalesce(btrim(p_settlement_id),'') = '' then
    raise exception 'SETTLEMENT_NOT_FOUND';
  end if;

  if not exists(
    select 1
    from public.adt_daily_sales_settlements s
    where s.team_id::text = p_team_id
      and s.id::text = p_settlement_id
  ) then
    raise exception 'SETTLEMENT_NOT_FOUND';
  end if;

  delete from public.adt_daily_sales
   where team_id::text = p_team_id
     and settlement_id::text = p_settlement_id;

  delete from public.adt_daily_sales_settlements
   where team_id::text = p_team_id
     and id::text = p_settlement_id;

  return true;
end;
$$;

revoke all on function public.adt_daily_sales_delete_settlement(text,text,text) from public;
grant execute on function public.adt_daily_sales_delete_settlement(text,text,text) to anon, authenticated;
notify pgrst, 'reload schema';
