-- =============================================================
-- ADT Stock v169 - Member sales permission + manager-only shortage delete
-- شغّل هذا الملف مرة واحدة في Supabase SQL Editor بعد ملفات الإعداد الحالية.
-- =============================================================

create extension if not exists pgcrypto;

-- 1) صلاحية مستقلة للمبيعات اليومية.
alter table public.team_members
    add column if not exists can_manage_daily_sales boolean not null default false;

-- أعمدة تستخدمها واجهة الصلاحيات الحالية؛ الإضافة آمنة لو كانت موجودة بالفعل.
alter table public.team_members
    add column if not exists can_create_invoices boolean not null default false;
alter table public.team_members
    add column if not exists can_edit_sell_price boolean not null default false;
alter table public.team_members
    add column if not exists can_manage_catalog boolean not null default false;

-- 2) ضمان وجود جداول المبيعات اليومية حتى يكون ملف v169 قابلاً للتشغيل بأمان.
create table if not exists public.adt_daily_sales (
    id uuid primary key default gen_random_uuid(),
    team_id uuid not null references public.teams(id) on delete cascade,
    amount numeric(18,2) not null check (amount > 0),
    note text,
    sale_at timestamptz not null default now(),
    created_by text not null,
    settlement_id uuid,
    created_at timestamptz not null default now()
);

create table if not exists public.adt_daily_sales_settlements (
    id uuid primary key default gen_random_uuid(),
    team_id uuid not null references public.teams(id) on delete cascade,
    total_amount numeric(18,2) not null check (total_amount >= 0),
    entries_count integer not null default 0,
    period_start timestamptz,
    period_end timestamptz,
    settled_by text not null,
    settled_at timestamptz not null default now()
);

do $$ begin
    alter table public.adt_daily_sales
      add constraint adt_daily_sales_settlement_fk
      foreign key (settlement_id)
      references public.adt_daily_sales_settlements(id)
      on delete set null;
exception when duplicate_object then null;
end $$;

create index if not exists adt_daily_sales_team_open_idx
    on public.adt_daily_sales(team_id, settlement_id, sale_at desc);
create index if not exists adt_daily_sales_settlements_team_idx
    on public.adt_daily_sales_settlements(team_id, settled_at desc);

alter table public.adt_daily_sales enable row level security;
alter table public.adt_daily_sales_settlements enable row level security;
revoke all on public.adt_daily_sales from public, anon, authenticated;
revoke all on public.adt_daily_sales_settlements from public, anon, authenticated;

-- 3) المدير الرئيسي فقط يملك العمليات الحساسة مثل تصفية المبيعات.
create or replace function public.adt_daily_sales_manager_allowed(
    p_team_id text,
    p_user_id text
)
returns boolean
language sql
security definer
set search_path = public
as $$
    select exists(
        select 1
        from public.team_members m
        where m.team_id::text = p_team_id
          and m.user_id = p_user_id
          and m.status = 'approved'
          and m.role = 'manager'
    );
$$;

revoke all on function public.adt_daily_sales_manager_allowed(text,text) from public;
grant execute on function public.adt_daily_sales_manager_allowed(text,text) to anon, authenticated;

-- المدير يدخل دائماً، والعضو يدخل فقط عند تفعيل صلاحية المبيعات اليومية له.
create or replace function public.adt_daily_sales_access_allowed(
    p_team_id text,
    p_user_id text
)
returns boolean
language sql
security definer
set search_path = public
as $$
    select exists(
        select 1
        from public.team_members m
        where m.team_id::text = p_team_id
          and m.user_id = p_user_id
          and m.status = 'approved'
          and (
              m.role = 'manager'
              or coalesce(m.can_manage_daily_sales,false) = true
          )
    );
$$;

revoke all on function public.adt_daily_sales_access_allowed(text,text) from public;
grant execute on function public.adt_daily_sales_access_allowed(text,text) to anon, authenticated;

-- 4) إضافة المبيعات: المدير أو العضو المسموح له.
create or replace function public.adt_daily_sales_add(
    p_team_id text,
    p_user_id text,
    p_amount numeric,
    p_note text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    r public.adt_daily_sales%rowtype;
begin
    if not public.adt_daily_sales_access_allowed(p_team_id,p_user_id) then
        raise exception 'NOT_ALLOWED';
    end if;

    if coalesce(p_amount,0) <= 0 then
        raise exception 'INVALID_AMOUNT';
    end if;

    insert into public.adt_daily_sales(team_id,amount,note,created_by)
    values(
        p_team_id::uuid,
        round(p_amount::numeric,2),
        nullif(left(trim(coalesce(p_note,'')),300),''),
        p_user_id
    )
    returning * into r;

    return to_jsonb(r);
end;
$$;

revoke all on function public.adt_daily_sales_add(text,text,numeric,text) from public;
grant execute on function public.adt_daily_sales_add(text,text,numeric,text) to anon, authenticated;

-- 5) عرض المبيعات: المدير أو العضو المسموح له.
create or replace function public.adt_daily_sales_fetch(
    p_team_id text,
    p_user_id text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_current jsonb;
    v_settlements jsonb;
    v_total numeric(18,2);
begin
    if not public.adt_daily_sales_access_allowed(p_team_id,p_user_id) then
        raise exception 'NOT_ALLOWED';
    end if;

    select coalesce(sum(amount),0)::numeric(18,2)
      into v_total
      from public.adt_daily_sales
     where team_id::text=p_team_id
       and settlement_id is null;

    select coalesce(jsonb_agg(to_jsonb(x) order by x.sale_at desc),'[]'::jsonb)
      into v_current
      from (
        select id,amount,note,sale_at,created_at
          from public.adt_daily_sales
         where team_id::text=p_team_id
           and settlement_id is null
         order by sale_at desc
         limit 500
      ) x;

    select coalesce(jsonb_agg(to_jsonb(s) order by s.settled_at desc),'[]'::jsonb)
      into v_settlements
      from (
        select id,total_amount,entries_count,period_start,period_end,settled_at
          from public.adt_daily_sales_settlements
         where team_id::text=p_team_id
         order by settled_at desc
         limit 100
      ) s;

    return jsonb_build_object(
        'current_total',v_total,
        'current',v_current,
        'settlements',v_settlements
    );
end;
$$;

revoke all on function public.adt_daily_sales_fetch(text,text) from public;
grant execute on function public.adt_daily_sales_fetch(text,text) to anon, authenticated;

-- 6) التصفية تبقى للمدير الرئيسي فقط، حتى لو العضو عنده صلاحية المبيعات.
create or replace function public.adt_daily_sales_settle(
    p_team_id text,
    p_user_id text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_total numeric(18,2);
    v_count integer;
    v_start timestamptz;
    v_end timestamptz;
    v_settlement public.adt_daily_sales_settlements%rowtype;
begin
    if not public.adt_daily_sales_manager_allowed(p_team_id,p_user_id) then
        raise exception 'NOT_ALLOWED';
    end if;

    perform id
      from public.adt_daily_sales
     where team_id::text=p_team_id
       and settlement_id is null
     for update;

    select coalesce(sum(amount),0)::numeric(18,2),
           count(*)::integer,
           min(sale_at),
           max(sale_at)
      into v_total,v_count,v_start,v_end
      from public.adt_daily_sales
     where team_id::text=p_team_id
       and settlement_id is null;

    if coalesce(v_count,0)=0 or coalesce(v_total,0)<=0 then
        raise exception 'NO_OPEN_SALES';
    end if;

    insert into public.adt_daily_sales_settlements(
        team_id,total_amount,entries_count,period_start,period_end,settled_by
    ) values(
        p_team_id::uuid,v_total,v_count,v_start,v_end,p_user_id
    ) returning * into v_settlement;

    update public.adt_daily_sales
       set settlement_id=v_settlement.id
     where team_id::text=p_team_id
       and settlement_id is null;

    return to_jsonb(v_settlement);
end;
$$;

revoke all on function public.adt_daily_sales_settle(text,text) from public;
grant execute on function public.adt_daily_sales_settle(text,text) to anon, authenticated;

-- 7) حفظ صلاحية المبيعات اليومية من شاشة إدارة صلاحيات العضو.
create or replace function public.adt_manager_member_action(
    p_team_id text,
    p_manager_user_id text,
    p_member_id text,
    p_action text,
    p_payload jsonb
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_team uuid;
    v_allowed boolean;
    v_sub boolean;
begin
    begin
        v_team := p_team_id::uuid;
    exception when others then
        return false;
    end;

    select exists(
        select 1
        from public.team_members m
        where m.team_id=v_team
          and m.user_id=p_manager_user_id
          and m.status='approved'
          and m.role='manager'
    ) into v_allowed;

    if not v_allowed then return false; end if;

    if not exists(
        select 1
        from public.team_members
        where id::text=p_member_id
          and team_id=v_team
          and role<>'manager'
    ) then
        return false;
    end if;

    if p_action='approve' then
        update public.team_members
           set status='approved'
         where id::text=p_member_id
           and team_id=v_team;

    elsif p_action in ('reject','remove') then
        delete from public.team_members
         where id::text=p_member_id
           and team_id=v_team
           and role<>'manager';

    elsif p_action='permissions' then
        v_sub := coalesce((p_payload->>'sub_manager')::boolean,false);

        update public.team_members set
            role = case when v_sub then 'sub_manager' else 'member' end,
            can_edit_products = v_sub or coalesce((p_payload->>'can_edit_products')::boolean,false),
            can_manage_shortages = v_sub or coalesce((p_payload->>'can_manage_shortages')::boolean,false),
            can_edit_debts = v_sub or coalesce((p_payload->>'can_edit_debts')::boolean,false),
            can_audit = v_sub or coalesce((p_payload->>'can_audit')::boolean,false),
            can_view_cost = v_sub or coalesce((p_payload->>'can_view_cost')::boolean,false),
            can_create_invoices = v_sub or coalesce((p_payload->>'can_create_invoices')::boolean,false),
            can_edit_sell_price = case
                when p_payload ? 'can_edit_sell_price' then v_sub or coalesce((p_payload->>'can_edit_sell_price')::boolean,false)
                else can_edit_sell_price
            end,
            can_manage_catalog = case
                when p_payload ? 'can_manage_catalog' then v_sub or coalesce((p_payload->>'can_manage_catalog')::boolean,false)
                else can_manage_catalog
            end,
            -- المبيعات اليومية لا تُمنح تلقائياً للمدير المساعد؛ يجب تشغيل زرها صراحة.
            can_manage_daily_sales = coalesce((p_payload->>'can_manage_daily_sales')::boolean,false)
        where id::text=p_member_id
          and team_id=v_team
          and role<>'manager';

    else
        return false;
    end if;

    return found;
end;
$$;

revoke all on function public.adt_manager_member_action(text,text,text,text,jsonb) from public;
grant execute on function public.adt_manager_member_action(text,text,text,text,jsonb) to anon, authenticated;

-- 8) قراءة صلاحيات عضو من المدير؛ to_jsonb يعيد الصلاحية الجديدة تلقائياً.
create or replace function public.adt_read_member_permissions(
    p_team_id text,
    p_manager_user_id text,
    p_member_id text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_team uuid;
    v_allowed boolean;
    r jsonb;
begin
    begin
        v_team := p_team_id::uuid;
    exception when others then
        return null;
    end;

    select exists(
        select 1
        from public.team_members m
        where m.team_id=v_team
          and m.user_id=p_manager_user_id
          and m.status='approved'
          and m.role='manager'
    ) into v_allowed;

    if not v_allowed then return null; end if;

    select to_jsonb(m)
      into r
      from public.team_members m
     where m.team_id=v_team
       and m.id::text=p_member_id
       and m.role<>'manager'
     limit 1;

    return r;
end;
$$;

revoke all on function public.adt_read_member_permissions(text,text,text) from public;
grant execute on function public.adt_read_member_permissions(text,text,text) to anon, authenticated;

-- 9) حذف النواقص: المدير الرئيسي فقط. العضو المسموح له يظل قادراً على الإضافة.
create or replace function public.adt_shortage_remove(
    p_team_id text,
    p_user_id text,
    p_dev_pin text,
    p_product_name text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_allowed boolean := false;
begin
    select exists(
        select 1
        from public.team_members m
        where m.team_id::text=p_team_id
          and m.user_id=p_user_id
          and m.status='approved'
          and m.role='manager'
    ) into v_allowed;

    if not v_allowed and coalesce(p_dev_pin,'')<>'' then
        v_allowed := public.adt_secret_matches('developer_pin',p_dev_pin);
    end if;

    if not v_allowed then return false; end if;

    delete from public.shortages
     where team_id::text=p_team_id
       and product_name=p_product_name;

    return true;
end;
$$;

revoke all on function public.adt_shortage_remove(text,text,text,text) from public;
grant execute on function public.adt_shortage_remove(text,text,text,text) to anon, authenticated;

notify pgrst, 'reload schema';

select 'ADT Stock v169 permissions installed successfully' as result;
