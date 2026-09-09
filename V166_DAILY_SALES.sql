-- =============================================================
-- ADT Stock v166 - Daily Sales
-- سجل المبيعات اليومية + التصفية الدورية
-- شغّل الملف مرة واحدة في Supabase SQL Editor
-- =============================================================

create extension if not exists pgcrypto;

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

-- المدير الرئيسي فقط يدير المبيعات اليومية.
create or replace function public.adt_daily_sales_manager_allowed(p_team_id text, p_user_id text)
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
    if not public.adt_daily_sales_manager_allowed(p_team_id,p_user_id) then
        raise exception 'NOT_ALLOWED';
    end if;
    if coalesce(p_amount,0) <= 0 then
        raise exception 'INVALID_AMOUNT';
    end if;

    insert into public.adt_daily_sales(team_id,amount,note,created_by)
    values(p_team_id::uuid,round(p_amount::numeric,2),nullif(left(trim(coalesce(p_note,'')),300),''),p_user_id)
    returning * into r;

    return to_jsonb(r);
end;
$$;

revoke all on function public.adt_daily_sales_add(text,text,numeric,text) from public;
grant execute on function public.adt_daily_sales_add(text,text,numeric,text) to anon, authenticated;

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
    if not public.adt_daily_sales_manager_allowed(p_team_id,p_user_id) then
        raise exception 'NOT_ALLOWED';
    end if;

    select coalesce(sum(amount),0)::numeric(18,2)
      into v_total
      from public.adt_daily_sales
     where team_id::text=p_team_id and settlement_id is null;

    select coalesce(jsonb_agg(to_jsonb(x) order by x.sale_at desc),'[]'::jsonb)
      into v_current
      from (
        select id,amount,note,sale_at,created_at
          from public.adt_daily_sales
         where team_id::text=p_team_id and settlement_id is null
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

    -- اقفل عمليات الدورة الحالية أولاً لمنع تصفيتين في نفس اللحظة.
    perform id
      from public.adt_daily_sales
     where team_id::text=p_team_id and settlement_id is null
     for update;

    select coalesce(sum(amount),0)::numeric(18,2),count(*)::integer,min(sale_at),max(sale_at)
      into v_total,v_count,v_start,v_end
      from public.adt_daily_sales
     where team_id::text=p_team_id and settlement_id is null;

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
     where team_id::text=p_team_id and settlement_id is null;

    return to_jsonb(v_settlement);
end;
$$;

revoke all on function public.adt_daily_sales_settle(text,text) from public;
grant execute on function public.adt_daily_sales_settle(text,text) to anon, authenticated;

select 'ADT Stock v166 daily sales installed' as result;
