-- =============================================================
-- ADT Stock v165 - Member login from the unified login screen
-- Run this ONCE in Supabase SQL Editor.
-- Keeps the existing 3-argument adt_unified_login for manager login.
-- Adds a 4-argument overload used when a member enters team code.
-- =============================================================

create extension if not exists pgcrypto;

-- Required by the newer join flow. Safe if it already exists.
alter table public.team_members
  add column if not exists member_phone text;

-- Normalize already stored phones without changing empty/null values.
update public.team_members
set member_phone = regexp_replace(member_phone, '[^0-9+]', '', 'g')
where member_phone is not null and member_phone <> '';

-- Remove only the four-argument overload if an older test version exists.
drop function if exists public.adt_unified_login(text,text,text,text);

create or replace function public.adt_unified_login(
    p_phone text,
    p_password text,
    p_device_id text,
    p_team_code text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_phone text := regexp_replace(coalesce(p_phone,''), '[^0-9+]', '', 'g');
    v_alt text;
    v_code text := upper(trim(coalesce(p_team_code,'')));
    v_hash text;
    v_scope text;
    v_ok boolean := false;
    t public.teams%rowtype;
    m public.team_members%rowtype;
begin
    if v_phone = '' or coalesce(p_password,'') = '' or
       coalesce(trim(p_device_id),'') = '' or v_code = '' then
        raise exception 'INVALID_TEAM_CREDENTIALS';
    end if;

    if left(v_phone,1) = '0' then
        v_alt := substr(v_phone,2);
    else
        v_alt := '0' || v_phone;
    end if;

    v_scope := 'member_login:' || v_code || ':' || lower(v_phone);
    if not public.adt_auth_is_allowed(v_scope, p_device_id) then
        raise exception 'TOO_MANY_ATTEMPTS';
    end if;

    select tm.* into t
    from public.teams tm
    where upper(trim(tm.code)) = v_code
    limit 1;

    if not found then
        perform public.adt_auth_record(v_scope, p_device_id, false);
        raise exception 'INVALID_TEAM_CREDENTIALS';
    end if;

    select password_hash into v_hash
    from public.team_secrets
    where team_id = t.id;

    v_ok := found and v_hash = crypt(p_password, v_hash);
    perform public.adt_auth_record(v_scope, p_device_id, v_ok);
    if not v_ok then
        raise exception 'INVALID_TEAM_CREDENTIALS';
    end if;

    -- Existing member identity is the phone number, not the old device id.
    select tm.* into m
    from public.team_members tm
    where tm.team_id = t.id
      and tm.role <> 'manager'
      and (
        regexp_replace(coalesce(tm.member_phone,''), '[^0-9+]', '', 'g') = v_phone
        or regexp_replace(coalesce(tm.member_phone,''), '[^0-9+]', '', 'g') = v_alt
      )
    order by tm.created_at asc nulls last
    limit 1
    for update;

    -- Compatibility for an older approved member created before member_phone existed:
    -- on the same device only, bind the entered phone once without losing permissions/name.
    if not found then
        select tm.* into m
        from public.team_members tm
        where tm.team_id = t.id
          and tm.role <> 'manager'
          and tm.user_id = p_device_id
          and tm.status = 'approved'
          and coalesce(trim(tm.member_phone),'') = ''
        order by tm.created_at asc nulls last
        limit 1
        for update;

        if found then
            update public.team_members
            set member_phone = v_phone
            where id = m.id;
            select * into m from public.team_members where id = m.id;
        end if;
    end if;

    if m.id is null then
        raise exception 'MEMBER_NOT_FOUND';
    end if;

    if coalesce(m.status,'pending') <> 'approved' then
        raise exception 'MEMBER_NOT_APPROVED';
    end if;

    -- Rebind the approved member to the current installation/device.
    -- Name, role and all permissions stay unchanged.
    update public.team_members
    set user_id = p_device_id,
        member_phone = v_phone
    where id = m.id;

    select * into m from public.team_members where id = m.id;

    update public.teams
    set last_seen_at = now()
    where id = t.id;

    select * into t from public.teams where id = t.id;

    return jsonb_build_object(
        'team', to_jsonb(t) - 'password' - 'lock_pin',
        'member', to_jsonb(m),
        'role', coalesce(m.role,'member'),
        'session_token', ''
    );
end;
$$;

revoke all on function public.adt_unified_login(text,text,text,text) from public;
grant execute on function public.adt_unified_login(text,text,text,text) to anon, authenticated;

select 'ADT Stock v165 member login installed' as result;
